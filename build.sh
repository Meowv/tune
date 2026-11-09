#!/usr/bin/env bash
# 构建 Debug 或 Release APK。
# 用法：./build.sh [debug|release] [额外的 Gradle 参数...]
# 默认 debug；release 使用本地 keystore.properties 签名，缺失时产出未签名包。
# release 成功后自动把 APK 与 stable.json 发布到 app/build/meowvtune/。
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# 版本清单里 APK 的托管目录；换 CDN 时改这个环境变量即可。
APK_BASE_URL="${APK_BASE_URL:-https://studio.meowv.com/data/meowvtune}"
# 更新日志：默认读仓库根目录的 release-notes.txt（每行一条），不存在则为空数组。
RELEASE_NOTES_FILE="${RELEASE_NOTES_FILE:-$PROJECT_DIR/release-notes.txt}"
PUBLISH_DIR="$PROJECT_DIR/app/build/meowvtune"
# 同工作区的 meowv.com 项目里 studio 静态产物目录，发布后同步过去，方便直接部署。
STUDIO_DATA_DIR="${STUDIO_DATA_DIR:-$PROJECT_DIR/../meowv.com/build/studio.meowv.com/data}"
[[ -d "$STUDIO_DATA_DIR" ]] && STUDIO_DATA_DIR="$(cd "$STUDIO_DATA_DIR" && pwd -P)"

usage() {
    cat <<'EOF'
用法：./build.sh [debug|release] [额外的 Gradle 参数...]

  debug     构建 Debug APK（默认）
  release   构建 Release APK（R8 + 资源压缩；有 keystore.properties 时自动签名）
            产物同时发布到 app/build/meowvtune/：MeowvTune-<版本>.apk + stable.json

示例：
  ./build.sh
  ./build.sh release
  ./build.sh release --offline

可覆盖的环境变量：
  APK_BASE_URL       清单里 APK 的托管目录（默认 https://studio.meowv.com/data/meowvtune）
  RELEASE_NOTES_FILE 更新日志文本，每行一条（默认仓库根目录 release-notes.txt，可缺省）
  STUDIO_DATA_DIR    meowv.com 的 studio 产物目录（默认 ../meowv.com/build/studio.meowv.com/data）
EOF
}

MODE=debug
case "${1:-}" in
    release|--release|-r) MODE=release; shift ;;
    debug|--debug|-d) MODE=debug; shift ;;
    -h|--help) usage; exit 0 ;;
    "") ;;
    -*) ;;  # 其余选项原样交给 Gradle，如 -q、--offline
    *)
        echo "未知参数：$1" >&2
        usage >&2
        exit 1
        ;;
esac

if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null 2>&1; then
    echo "构建失败，请安装 JDK 17 并配置 JAVA_HOME。" >&2
    exit 1
fi

if [[ ! -f local.properties && -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    if [[ -d "$HOME/Library/Android/sdk" ]]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    else
        echo "构建失败，请通过 ANDROID_HOME 或 local.properties 配置 Android SDK。" >&2
        exit 1
    fi
fi

if [[ "$MODE" == "release" ]]; then
    TASK=":app:assembleRelease"
    if [[ ! -f keystore.properties ]]; then
        echo "提示：未找到 keystore.properties，Release 将产出未签名包。" >&2
    fi
else
    TASK=":app:assembleDebug"
fi

./gradlew "$TASK" "$@"

if [[ "$MODE" == "release" ]]; then
    # 签名配置存在时产物为 app-release.apk，否则为 app-release-unsigned.apk；
    # 取最新文件，避免旧产物掩盖本次构建结果。
    APK_PATH="$(ls -t "$PROJECT_DIR"/app/build/outputs/apk/release/app-release*.apk 2>/dev/null | head -1 || true)"
else
    APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

if [[ -z "${APK_PATH:-}" || ! -f "$APK_PATH" ]]; then
    echo "构建结束，但未找到 APK。" >&2
    exit 1
fi

SIZE="$(wc -c < "$APK_PATH" | tr -d ' ')"
printf '\nAPK 已生成（%s）\n%s\n大小：%s 字节\n' "$MODE" "$APK_PATH" "$SIZE"

# 版本信息一律从产物里读，避免清单与安装包对不上。
sdk_dir() {
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        printf '%s' "$ANDROID_HOME"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        printf '%s' "$ANDROID_SDK_ROOT"
    elif [[ -f local.properties ]]; then
        sed -n 's/^sdk\.dir=//p' local.properties | head -1
    fi
}

find_aapt() {
    local sdk build_tools
    sdk="$(sdk_dir)"
    if [[ -n "$sdk" && -d "$sdk/build-tools" ]]; then
        build_tools="$(ls -1 "$sdk/build-tools" | sort -V | tail -1)"
        if [[ -x "$sdk/build-tools/$build_tools/aapt" ]]; then
            printf '%s' "$sdk/build-tools/$build_tools/aapt"
            return 0
        fi
    fi
    command -v aapt 2>/dev/null
}

publish_release() {
    local apk="$1"
    local aapt badging package version_name version_code min_sdk channel
    local sha256 size apk_name apk_url notes notes_count

    aapt="$(find_aapt || true)"
    if [[ -z "$aapt" ]]; then
        echo "未找到 aapt，跳过 stable.json（APK 仍保留在 outputs 目录）。" >&2
        return 0
    fi

    badging="$("$aapt" dump badging "$apk")"
    package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    min_sdk="$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    if [[ -z "$package" || -z "$version_name" || -z "$version_code" || -z "$min_sdk" ]]; then
        echo "无法从 APK 读取版本信息，跳过 stable.json。" >&2
        return 0
    fi

    channel="$(sed -n 's/.*"UPDATE_CHANNEL", "\\"\([^"\\]*\)\\"".*/\1/p' app/build.gradle.kts | head -1)"
    channel="${channel:-stable}"
    sha256="$(shasum -a 256 "$apk" | awk '{print $1}')"
    size="$(wc -c < "$apk" | tr -d ' ')"
    apk_name="MeowvTune-$version_name.apk"
    # APK 与清单放在同一目录，地址里不带版本号子路径。
    apk_url="${APK_BASE_URL%/}/$apk_name"
    notes=""
    if [[ -f "$RELEASE_NOTES_FILE" ]]; then
        notes="$(cat "$RELEASE_NOTES_FILE")"
    fi
    notes_count="$(printf '%s\n' "$notes" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"

    # 每次只保留本次发布的两个文件，避免旧版本混在上传目录里。
    rm -rf "$PUBLISH_DIR"
    mkdir -p "$PUBLISH_DIR"
    cp "$apk" "$PUBLISH_DIR/$apk_name"

    # 未签名的包不能发给客户端，清单也就不该生成。
    if [[ "$apk" == *unsigned* ]]; then
        printf '\n已拷贝 %s，但未生成 stable.json：APK 未签名。\n' "$PUBLISH_DIR/$apk_name" >&2
        return 0
    fi

    M_PACKAGE="$package" M_CHANNEL="$channel" M_VERSION_NAME="$version_name" \
        M_VERSION_CODE="$version_code" M_MIN_SDK="$min_sdk" M_TITLE="MeowvTune $version_name" \
        M_NOTES="$notes" M_APK_URL="$apk_url" M_SIZE_BYTES="$size" M_SHA256="$sha256" \
        M_RELEASE_DATE="$(date +%F)" \
        python3 - > "$PUBLISH_DIR/stable.json" <<'PY'
import json, os, re, sys

manifest = {
    "schemaVersion": 1,
    "packageName": os.environ["M_PACKAGE"],
    "channel": os.environ["M_CHANNEL"],
    "versionName": os.environ["M_VERSION_NAME"],
    "versionCode": int(os.environ["M_VERSION_CODE"]),
    "minSdkVersion": int(os.environ["M_MIN_SDK"]),
    "releaseDate": os.environ["M_RELEASE_DATE"],
    "title": os.environ["M_TITLE"],
    "releaseNotes": [line.strip() for line in os.environ["M_NOTES"].splitlines() if line.strip()],
    "apk": {
        "url": os.environ["M_APK_URL"],
        "sizeBytes": int(os.environ["M_SIZE_BYTES"]),
        "sha256": os.environ["M_SHA256"],
    },
}
if not manifest["apk"]["url"].startswith("https://"):
    sys.exit("apk.url 必须是 HTTPS")
if not re.fullmatch(r"[0-9a-fA-F]{64}", manifest["apk"]["sha256"]):
    sys.exit("sha256 必须是 64 位十六进制")
if manifest["versionCode"] <= 0:
    sys.exit("versionCode 必须大于 0")
print(json.dumps(manifest, ensure_ascii=False, indent=2))
PY

    printf '\n发布产物（%s）\n' "$PUBLISH_DIR"
    printf '  %s\n  stable.json（%s · versionCode %s · %s 条更新日志）\n' \
        "$apk_name" "$version_name" "$version_code" "$notes_count"

    sync_to_studio "$apk_name"
}

# 把本次发布的 APK 与清单同步到 meowv.com 的 studio 产物目录；目录不存在时只提示。
sync_to_studio() {
    local apk_name="$1"
    local target="$STUDIO_DATA_DIR/meowvtune"

    if [[ ! -d "$STUDIO_DATA_DIR" ]]; then
        printf '\n未同步到 studio 产物目录（不存在）：%s\n' "$STUDIO_DATA_DIR" >&2
        return 0
    fi
    rm -rf "$target"
    mkdir -p "$target"
    cp "$PUBLISH_DIR/$apk_name" "$PUBLISH_DIR/stable.json" "$target/"
    printf '  已同步到 %s\n' "$target"
}

if [[ "$MODE" == "release" ]]; then
    printf 'SHA-256：%s\n' "$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
    if [[ "$APK_PATH" == *unsigned* ]]; then
        printf '签名：未签名（缺少 keystore.properties）\n'
    else
        printf '签名：已签名（keystore.properties）\n'
    fi
    publish_release "$APK_PATH"
fi
