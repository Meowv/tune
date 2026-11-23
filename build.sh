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
用法：./build.sh [debug|release] [选项] [额外的 Gradle 参数...]

  debug       构建 Debug APK（默认）
  release     构建 Release APK（R8 + 资源压缩；有 keystore.properties 时自动签名）
              产物同时发布到 app/build/meowvtune/：MeowvTune-<版本>.apk + stable.json

  --bump      把 versionCode +1 后构建（构建失败自动回滚），用于正式发版
              默认不递增，沿用当前 versionCode，避免只涨号不出包

versionName 始终由人工维护。

示例：
  ./build.sh
  ./build.sh release
  ./build.sh release --bump
  ./build.sh release --offline

可覆盖的环境变量：
  APK_BASE_URL       清单里 APK 的托管目录（默认 https://studio.meowv.com/data/meowvtune）
  RELEASE_NOTES_FILE 更新日志文本，每行一条（默认仓库根目录 release-notes.txt，可缺省）
  STUDIO_DATA_DIR    meowv.com 的 studio 产物目录（默认 ../meowv.com/build/studio.meowv.com/data）
EOF
}

MODE=debug
BUMP=0
GRADLE_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        release|--release|-r) MODE=release ;;
        debug|--debug|-d) MODE=debug ;;
        --bump) BUMP=1 ;;
        -h|--help) usage; exit 0 ;;
        -*) GRADLE_ARGS+=("$1") ;;  # 其余选项原样交给 Gradle，如 -q、--offline
        *)
            echo "未知参数：$1" >&2
            usage >&2
            exit 1
            ;;
    esac
    shift
done

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

VERSION_FILE="$PROJECT_DIR/app/build.gradle.kts"
ORIGINAL_VERSION_CODE=""

read_version_code() {
    sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\)[[:space:]]*$/\1/p' "$VERSION_FILE" | head -1
}

read_version_name() {
    sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"[[:space:]]*$/\1/p' "$VERSION_FILE" | head -1
}

write_version_code() {
    local value="$1"
    sed -i.bak -E "s/^([[:space:]]*versionCode = )[0-9]+[[:space:]]*$/\1$value/" "$VERSION_FILE"
    rm -f "$VERSION_FILE.bak"
}

# 仅当显式传入 --bump 时把 versionCode +1；versionName 始终由人工按语义化版本调整。
# 构建失败时回滚，避免只涨号不出包。
bump_version_code() {
    local current next
    current="$(read_version_code)"
    if [[ -z "$current" ]]; then
        echo "未在 app/build.gradle.kts 找到 versionCode = <数字>，已停止发布构建。" >&2
        exit 1
    fi
    next=$((current + 1))
    ORIGINAL_VERSION_CODE="$current"
    write_version_code "$next"
    if [[ "$(read_version_code)" != "$next" ]]; then
        echo "versionCode 写入失败，已停止发布构建。" >&2
        exit 1
    fi
    printf '版本号：versionCode %s → %s（versionName 保持 %s）\n' "$current" "$next" "$(read_version_name)"
}

rollback_version_code() {
    local status=$?
    if [[ "$status" -ne 0 && -n "$ORIGINAL_VERSION_CODE" ]]; then
        write_version_code "$ORIGINAL_VERSION_CODE"
        printf '构建失败，versionCode 已回滚到 %s。\n' "$ORIGINAL_VERSION_CODE" >&2
    fi
}

if [[ "$MODE" == "release" ]]; then
    TASK=":app:assembleRelease"
    if [[ ! -f keystore.properties ]]; then
        echo "提示：未找到 keystore.properties，Release 将产出未签名包。" >&2
    fi
    if [[ "$BUMP" -eq 1 ]]; then
        trap rollback_version_code EXIT
        bump_version_code
    else
        printf '未指定 --bump，沿用 versionCode %s\n' "$(read_version_code)"
    fi
else
    TASK=":app:assembleDebug"
fi

./gradlew "$TASK" ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"}

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
    local apk_name apk_url

    if [[ "$apk" == *unsigned* ]]; then
        printf '\n未生成发布目录：APK 未签名。产物保留在 outputs 目录。\n' >&2
        if [[ "$BUMP" -eq 1 ]]; then return 1; fi
        return 0
    fi

    aapt="$(find_aapt || true)"
    if [[ -z "$aapt" ]]; then
        echo "未找到 aapt，APK 保留在 outputs 目录。" >&2
        return 1
    fi

    badging="$("$aapt" dump badging "$apk")"
    package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    min_sdk="$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    if [[ -z "$package" || -z "$version_name" || -z "$version_code" || -z "$min_sdk" ]]; then
        echo "无法从 APK 读取版本信息。" >&2
        return 1
    fi

    channel="$(sed -n 's/.*"UPDATE_CHANNEL", "\\"\([^"\\]*\)\\"".*/\1/p' app/build.gradle.kts | head -1)"
    channel="${channel:-stable}"
    if [[ ! "$version_name" =~ ^[0-9A-Za-z][0-9A-Za-z._+-]*$ ]]; then
        echo "APK 版本名称不能用作发布文件名。" >&2
        return 1
    fi
    apk_name="MeowvTune-$version_name.apk"
    apk_url="${APK_BASE_URL%/}/$apk_name"

    M_PACKAGE="$package" M_CHANNEL="$channel" M_VERSION_NAME="$version_name" \
        M_VERSION_CODE="$version_code" M_MIN_SDK="$min_sdk" M_TITLE="MeowvTune $version_name" \
        M_APK_URL="$apk_url" M_RELEASE_DATE="$(date +%F)" \
        python3 - "$apk" "$apk_name" "$PUBLISH_DIR" "$STUDIO_DATA_DIR" "$RELEASE_NOTES_FILE" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

apk = Path(sys.argv[1])
apk_name = sys.argv[2]
publish, studio, notes_file = map(Path, sys.argv[3:])
targets = [publish]
if studio.is_dir():
    targets.append(studio / "meowvtune")

try:
    notes_text = notes_file.read_text(encoding="utf-8")
except FileNotFoundError:
    notes_text = ""
notes = notes_text.split("\n") if notes_text else []

def apk_details(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return path.stat().st_size, digest.hexdigest()

size, sha256 = apk_details(apk)
manifest = {
    "schemaVersion": 1,
    "packageName": os.environ["M_PACKAGE"],
    "channel": os.environ["M_CHANNEL"],
    "versionName": os.environ["M_VERSION_NAME"],
    "versionCode": int(os.environ["M_VERSION_CODE"]),
    "minSdkVersion": int(os.environ["M_MIN_SDK"]),
    "releaseDate": os.environ["M_RELEASE_DATE"],
    "title": os.environ["M_TITLE"],
    "releaseNotes": notes,
    "apk": {"url": os.environ["M_APK_URL"], "sizeBytes": size, "sha256": sha256},
}
if not manifest["apk"]["url"].startswith("https://"):
    sys.exit("apk.url 必须是 HTTPS")
if manifest["versionCode"] <= 0 or manifest["minSdkVersion"] <= 0 or size <= 0:
    sys.exit("APK 大小、版本码和最低 Android API 必须大于 0")
payload = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"

prepared = []
installed = []
committed = False
try:
    # 两处都准备好并校验后，再替换各自目录。
    for target in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        work = Path(tempfile.mkdtemp(prefix=".meowvtune-", dir=target.parent))
        prepared.append((target, work))
        staged = work / "new"
        staged.mkdir()
        copied = staged / apk_name
        shutil.copyfile(apk, copied)
        if apk_details(copied) != (size, sha256):
            raise OSError("发布 APK 的大小或摘要不一致")
        (staged / "stable.json").write_text(payload, encoding="utf-8")

    for target, work in prepared:
        if target.exists() or target.is_symlink():
            target.rename(work / "old")
        (work / "new").rename(target)
        installed.append((target, work))
    committed = True
finally:
    if not committed:
        for target, work in reversed(installed):
            # 保留刚换入的目录，给旧目录腾出原路径。
            try:
                target.rename(work / "new")
            except OSError as error:
                print(f"无法移走未完成的发布目录：{target}：{error}", file=sys.stderr)
        for target, work in reversed(prepared):
            backup = work / "old"
            if backup.exists() or backup.is_symlink():
                try:
                    backup.rename(target)
                except OSError as error:
                    print(f"无法还原旧发布目录，备份保留在 {backup}：{error}", file=sys.stderr)
    for _, work in prepared:
        backup = work / "old"
        if committed or not (backup.exists() or backup.is_symlink()):
            shutil.rmtree(work, ignore_errors=True)

print(f"\n发布产物（{publish}）")
print(f"  {apk_name}\n  stable.json（{manifest['versionName']} · versionCode {manifest['versionCode']}）")
if len(targets) > 1:
    print(f"  已同步到 {targets[1]}")
else:
    print(f"\n未同步到 studio 产物目录（不存在）：{studio}")
PY
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
