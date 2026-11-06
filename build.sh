#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

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

./gradlew :app:assembleDebug "$@"

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
    echo "构建结束，但未找到 Debug APK。" >&2
    exit 1
fi

printf '\nAPK 已生成\n%s\n' "$APK_PATH"
