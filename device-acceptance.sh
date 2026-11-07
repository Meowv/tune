#!/usr/bin/env bash
# T28 设备验收：安装当前构建、起播、后台连续播放并采样状态。
# 用法：./device-acceptance.sh <serial> [minutes]，默认 30 分钟。
set -uo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL="${1:-}"
MINUTES="${2:-30}"
OUT="$PROJECT_DIR/docs/t28"
APK="${APK:-$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk}"

if [[ -z "$SERIAL" ]]; then
    echo "用法：./device-acceptance.sh <serial> [minutes]" >&2
    "$ADB" devices -l
    exit 1
fi
if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "设备 $SERIAL 不可用。" >&2
    exit 1
fi
mkdir -p "$OUT"
LOG="$OUT/acceptance-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

model="$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
release="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
sdk="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
density="$("$ADB" -s "$SERIAL" shell wm density | tr -d '\r' | awk '{print $NF}')"
echo "设备：$model / Android $release (API $sdk) / $density"

echo "== 安装 =="
"$ADB" -s "$SERIAL" install -r "$APK" | tail -1

play_state() {
    "$ADB" -s "$SERIAL" shell dumpsys media_session 2>/dev/null | grep -m1 "state=PlaybackState" | grep -oE "state=[0-9]+" | head -1
}

echo "== 冷启动 =="
"$ADB" -s "$SERIAL" shell am force-stop com.meowv.tune
"$ADB" -s "$SERIAL" shell am start -W -n com.meowv.tune/.MainActivity | grep -E "TotalTime|Status" || true
sleep 4

echo "== 触发首个电台 =="
"$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/acceptance.xml >/dev/null 2>&1
BOUNDS="$("$ADB" -s "$SERIAL" shell cat /sdcard/acceptance.xml | tr '<' '\n<' \
    | grep 'content-desc="播放"' | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')"
if [[ -z "$BOUNDS" ]]; then
    echo "未找到播放按钮，请确认列表已加载（网络或地区范围）。" >&2
    exit 1
fi
read -r x1 y1 x2 y2 <<< "$BOUNDS"
"$ADB" -s "$SERIAL" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))

echo "== 等待起播 =="
started=0
for _ in $(seq 1 30); do
    sleep 2
    if [[ "$(play_state)" == "state=3" ]]; then started=1; break; fi
done
if [[ "$started" -ne 1 ]]; then
    echo "起播失败：$(play_state)" >&2
    exit 1
fi
echo "起播成功"

echo "== 切到后台并连续播放 ${MINUTES} 分钟 =="
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME
sleep 2
interruptions=0
for minute in $(seq 1 "$MINUTES"); do
    sleep 60
    state="$(play_state)"
    if [[ "$state" != "state=3" ]]; then
        interruptions=$((interruptions + 1))
        title="$("$ADB" -s "$SERIAL" shell dumpsys media_session 2>/dev/null | grep -m1 'metadata:' | sed -E 's/.*description=([^,]*),.*/\1/')"
        echo "第 ${minute} 分钟：状态 $state 台站 $title"
    fi
done
echo "后台播放结束，非播放采样 ${interruptions} 次"

echo "== 通知与媒体会话 =="
"$ADB" -s "$SERIAL" shell dumpsys notification --noredact 2>/dev/null \
    | grep -A3 "com.meowv.tune" | grep -E "tickerText|android.title|android.text" | head -6
"$ADB" -s "$SERIAL" shell dumpsys media_session 2>/dev/null \
    | grep -E "state=PlaybackState|metadata" | head -4

echo "== 冷启动复验（后台播放中回到前台） =="
"$ADB" -s "$SERIAL" shell am start -W -n com.meowv.tune/.MainActivity | grep -E "TotalTime|Status" || true
sleep 3
"$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/foreground-$(date +%H%M%S).png"

if [[ "$interruptions" -eq 0 ]]; then
    echo "结论：后台连续播放 ${MINUTES} 分钟无中断。"
else
    echo "结论：后台播放出现 ${interruptions} 次中断，需要排查。"
fi
echo "日志：$LOG"
