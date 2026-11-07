#!/usr/bin/env bash
# T27 兼容矩阵：在 API 28 模拟器上循环窗口尺寸、密度与字体缩放。
# 用法：./matrix.sh [serial]，默认 emulator-5556。
set -uo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL="${1:-${ANDROID_SERIAL:-emulator-5556}}"
LABEL="${2:-api28}"
OUT="$PROJECT_DIR/docs/t27/$LABEL"
RUNNER="com.meowv.tune.test/com.meowv.tune.TuneTestRunner"
mkdir -p "$OUT"

if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "设备 $SERIAL 不可用。" >&2
    exit 1
fi

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

reset_device() {
    "$ADB" -s "$SERIAL" shell wm size reset >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell wm density reset >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell settings put system font_scale 1.0 >/dev/null 2>&1
}

# 名称|尺寸|密度|字体缩放
CONFIGS=(
    "car-720p|1280x720|160|1.0"
    "car-1080p|1920x1080|240|1.0"
    "phone-1080x2340|1080x2340|420|1.0"
    "narrow-320dp|320x640|160|1.0"
    "small-window|800x480|160|1.0"
    "phone-font200|1080x2340|420|2.0"
    "car-font200|1280x720|160|2.0"
)

PASS=0
FAIL=0
: > "$OUT/matrix.log"
for entry in "${CONFIGS[@]}"; do
    IFS='|' read -r name size density font <<< "$entry"
    reset_device
    "$ADB" -s "$SERIAL" shell wm size "$size" >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell wm density "$density" >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell settings put system font_scale "$font" >/dev/null 2>&1
    sleep 2

    RESULT="$("$ADB" -s "$SERIAL" shell am instrument -w -e class com.meowv.tune.CompatibilityMatrixTest "$RUNNER" 2>&1 | tr -d '\r')"
    # 用例结束后 Activity 已关闭，重新拉起再截图作为版面证据。
    "$ADB" -s "$SERIAL" shell am start -n com.meowv.tune/.MainActivity >/dev/null 2>&1
    sleep 3
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
    "$ADB" -s "$SERIAL" logcat -d -s System.out:I 2>/dev/null | grep MATRIX | tail -1 | sed 's/.*System.out: //' >> "$OUT/matrix.log"
    "$ADB" -s "$SERIAL" logcat -c
    {
        echo "### $name $size @${density}dpi font=$font"
        printf '%s\n' "$RESULT" | grep -E "MATRIX|OK \(|Tests run|AssertionError" || true
        echo
    } >> "$OUT/matrix.log"

    if printf '%s' "$RESULT" | grep -q "^OK ("; then
        PASS=$((PASS + 1))
        echo "$name: PASS"
    else
        FAIL=$((FAIL + 1))
        echo "$name: FAIL"
    fi
done

reset_device
echo "矩阵完成：通过 ${PASS}，失败 ${FAIL}。日志：$OUT/matrix.log"
[[ "$FAIL" -eq 0 ]]
