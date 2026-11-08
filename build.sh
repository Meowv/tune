#!/usr/bin/env bash
# 构建 Debug 或 Release APK。
# 用法：./build.sh [debug|release] [额外的 Gradle 参数...]
# 默认 debug；release 使用本地 keystore.properties 签名，缺失时产出未签名包。
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

usage() {
    cat <<'EOF'
用法：./build.sh [debug|release] [额外的 Gradle 参数...]

  debug     构建 Debug APK（默认）
  release   构建 Release APK（R8 + 资源压缩；有 keystore.properties 时自动签名）

示例：
  ./build.sh
  ./build.sh release
  ./build.sh release --offline
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

if [[ "$MODE" == "release" ]]; then
    printf 'SHA-256：%s\n' "$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
    if [[ "$APK_PATH" == *unsigned* ]]; then
        printf '签名：未签名（缺少 keystore.properties）\n'
    else
        printf '签名：已签名（keystore.properties）\n'
    fi
fi
