#!/usr/bin/env bash
# 目标一回归门禁：静态检查 + JVM 单测 + 全量仪器用例。
# 用法：./verify.sh [serial]，默认 emulator-5556。
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL="${1:-${ANDROID_SERIAL:-emulator-5556}}"
REPORT_DIR="$PROJECT_DIR/docs/t25"
RUNNER="com.meowv.tune.test/com.meowv.tune.TuneTestRunner"

if [[ ! -x "$ADB" ]]; then
    echo "未找到 adb：$ADB" >&2
    exit 1
fi
if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "设备 $SERIAL 不可用，请先启动模拟器或连接设备。" >&2
    exit 1
fi

mkdir -p "$REPORT_DIR"
LOG="$REPORT_DIR/gate-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

echo "== 1/3 静态检查与 JVM 单测 =="
./check.sh

echo "== 2/3 构建 Debug 与测试 APK =="
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest

echo "== 3/3 全量仪器用例（${SERIAL}） =="
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

RESULT="$("$ADB" -s "$SERIAL" shell am instrument -w "$RUNNER" | tr -d '\r')"
printf '%s\n' "$RESULT"

if printf '%s' "$RESULT" | grep -q "FAILURES!!!"; then
    echo "门禁失败：存在未通过用例。" >&2
    exit 1
fi
if ! printf '%s' "$RESULT" | grep -q "^OK ("; then
    echo "门禁失败：未取得通过结果。" >&2
    exit 1
fi

echo "门禁通过。日志：$LOG"
