#!/usr/bin/env bash
# 启动 Android 模拟器，可选构建并安装 Debug/Release APK。
# 用法：./run.sh [debug|release] [选项]
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
DEFAULT_AVD="${DEFAULT_AVD:-tune_api30}"
PACKAGE="com.meowv.tune"
ACTIVITY="com.meowv.tune/.MainActivity"

# 车机模式默认 1280x720dp：1280x720 像素 @160dpi，与 T27 矩阵基线一致。
CAR_SIZE="${CAR_SIZE:-1280x720}"
CAR_DENSITY="${CAR_DENSITY:-160}"

usage() {
    cat <<EOF
用法：./run.sh [debug|release] [选项]

  debug|release    安装哪种包（默认 debug）
  --avd <name>     指定 AVD（默认 ${DEFAULT_AVD}）
  --port <port>    指定控制台端口（默认自动选择空闲偶数端口）
  --headless       无窗口启动（自动化用，同时关闭音频）
  --car            切换为车机大屏（${CAR_SIZE} @ ${CAR_DENSITY}dpi，横向）
  --reset          清除显示覆盖并退出（配合 --avd 指定目标）
  --no-install     只启动模拟器，不构建、不安装
  --no-build       跳过构建，直接安装现有 APK
  --fresh          安装前先卸载旧包（debug/release 签名不同时使用）
  -l, --list       列出可用 AVD
  -h, --help       显示本帮助

车机分辨率可用环境变量覆盖：
  CAR_SIZE=1920x1080 CAR_DENSITY=240 ./run.sh --car

注意：带挖孔/刘海的手机 AVD 会保留顶部 cutout 区（约 136px 黑边）；
     需要干净车机画布时，改用 automotive_* 设备规格或无挖孔的平板 AVD。

示例：
  ./run.sh                          # API 30 模拟器 + Debug 包
  ./run.sh release --avd tune_api33 # API 33 模拟器 + Release 包
  ./run.sh --car                    # 车机 1280x720dp + Debug
  ./run.sh --reset                  # 恢复默认分辨率与旋转
EOF
}

MODE=debug
AVD="$DEFAULT_AVD"
PORT=""
HEADLESS=0
INSTALL=1
BUILD=1
FRESH=0
CAR=0
RESET=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        debug|release) MODE="$1"; shift ;;
        --avd)
            [[ $# -ge 2 ]] || { echo "--avd 需要名称。" >&2; exit 1; }
            AVD="$2"; shift 2
            ;;
        --port)
            [[ $# -ge 2 ]] || { echo "--port 需要端口号。" >&2; exit 1; }
            PORT="$2"; shift 2
            ;;
        --headless) HEADLESS=1; shift ;;
        --car) CAR=1; shift ;;
        --reset) RESET=1; shift ;;
        --no-install) INSTALL=0; shift ;;
        --no-build) BUILD=0; shift ;;
        --fresh) FRESH=1; shift ;;
        -l|--list)
            "$EMULATOR" -list-avds
            exit 0
            ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "未知参数：$1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ ! -x "$ADB" ]]; then
    echo "未找到 adb：$ADB" >&2
    exit 1
fi
if [[ ! -x "$EMULATOR" ]]; then
    echo "未找到 emulator：$EMULATOR" >&2
    exit 1
fi
if [[ -z "$AVD" ]]; then
    echo "--avd 需要名称。" >&2
    exit 1
fi
if [[ -n "$PORT" && ! "$PORT" =~ ^[0-9]+$ ]]; then
    echo "--port 需要数字，当前：$PORT" >&2
    exit 1
fi
if [[ "$CAR" -eq 1 && "$RESET" -eq 1 ]]; then
    echo "--car 与 --reset 不能同时使用。" >&2
    exit 1
fi
if [[ ! "$CAR_SIZE" =~ ^[0-9]+x[0-9]+$ ]]; then
    echo "CAR_SIZE 格式应为 宽x高，当前：$CAR_SIZE" >&2
    exit 1
fi
if [[ ! "$CAR_DENSITY" =~ ^[0-9]+$ ]]; then
    echo "CAR_DENSITY 需要数字，当前：$CAR_DENSITY" >&2
    exit 1
fi
if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
    echo "AVD 不存在：$AVD" >&2
    echo "可用 AVD：" >&2
    "$EMULATOR" -list-avds >&2
    exit 1
fi

port_in_use() {
    local p="$1"
    if "$ADB" devices | grep -q "^emulator-$p[[:space:]]"; then
        return 0
    fi
    if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

find_running_serial() {
    local want="$1" serial state name
    while read -r serial state; do
        [[ "$state" == "device" ]] || continue
        name="$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)"
        if [[ "$name" == "$want" ]]; then
            printf '%s' "$serial"
            return 0
        fi
    done < <("$ADB" devices | tail -n +2)
    return 1
}

apply_car_display() {
    local s="$1" w h wdp hdp
    w="${CAR_SIZE%x*}"
    h="${CAR_SIZE#*x}"
    wdp=$((w * 160 / CAR_DENSITY))
    hdp=$((h * 160 / CAR_DENSITY))
    # 只改逻辑分辨率即可得到横向窗口；再锁定旋转会把宽高对调成竖屏。
    "$ADB" -s "$s" shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
    "$ADB" -s "$s" shell settings put system user_rotation 0 >/dev/null 2>&1 || true
    "$ADB" -s "$s" shell wm size "$CAR_SIZE" >/dev/null
    "$ADB" -s "$s" shell wm density "$CAR_DENSITY" >/dev/null
    sleep 1
    echo "车机显示：${wdp}x${hdp}dp（${CAR_SIZE} @ ${CAR_DENSITY}dpi）"
}

reset_display() {
    local s="$1"
    "$ADB" -s "$s" shell wm size reset >/dev/null 2>&1 || true
    "$ADB" -s "$s" shell wm density reset >/dev/null 2>&1 || true
    "$ADB" -s "$s" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
    "$ADB" -s "$s" shell settings put system user_rotation 0 >/dev/null 2>&1 || true
    echo "已恢复默认显示（${s}）"
}

if [[ "$RESET" -eq 1 ]]; then
    SERIAL="$(find_running_serial "$AVD" || true)"
    if [[ -z "$SERIAL" ]]; then
        echo "没有运行中的 ${AVD}，无需恢复。" >&2
        exit 1
    fi
    reset_display "$SERIAL"
    exit 0
fi

# 优先复用已经在跑的同一 AVD，避免重复开机。
SERIAL="$(find_running_serial "$AVD" || true)"
if [[ -n "$SERIAL" ]]; then
    echo "复用已运行的 ${AVD}（${SERIAL}）"
fi

if [[ -z "$SERIAL" ]]; then
    if [[ -n "$PORT" ]]; then
        if port_in_use "$PORT"; then
            echo "端口 $PORT 已被占用。" >&2
            exit 1
        fi
    else
        PORT=5554
        for _ in $(seq 1 30); do
            port_in_use "$PORT" || break
            PORT=$((PORT + 2))
        done
    fi
    SERIAL="emulator-$PORT"

    mkdir -p "$PROJECT_DIR/docs/run"
    LOG="$PROJECT_DIR/docs/run/emulator-$PORT.log"
    echo "启动 ${AVD}（${SERIAL}），日志：$LOG"
    if [[ "$HEADLESS" -eq 1 ]]; then
        nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -no-window -no-audio \
            -no-boot-anim -gpu swiftshader_indirect >"$LOG" 2>&1 &
    else
        nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -no-boot-anim >"$LOG" 2>&1 &
    fi

    echo "等待开机..."
    booted=0
    for i in $(seq 1 150); do
        if [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
            booted=1
            break
        fi
        if [[ $((i % 10)) -eq 0 ]]; then
            echo "  已等待 $((i * 2)) 秒"
        fi
        sleep 2
    done
    if [[ "$booted" -ne 1 ]]; then
        echo "开机超时，模拟器日志末尾：" >&2
        tail -20 "$LOG" >&2 || true
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        exit 1
    fi
    echo "开机完成（${SERIAL}）"
fi

if [[ "$CAR" -eq 1 ]]; then
    apply_car_display "$SERIAL"
fi

"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true

if [[ "$INSTALL" -eq 0 ]]; then
    echo "已启动 ${AVD}（${SERIAL}），跳过安装。"
    exit 0
fi

if [[ "$BUILD" -eq 1 ]]; then
    ./build.sh "$MODE"
fi

if [[ "$MODE" == "release" ]]; then
    APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
    if [[ ! -f "$APK" ]]; then
        echo "未找到已签名 Release APK：$APK" >&2
        echo "请确认 keystore.properties 存在后执行 ./build.sh release。" >&2
        exit 1
    fi
else
    APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi
if [[ ! -f "$APK" ]]; then
    echo "未找到 APK：$APK" >&2
    exit 1
fi

if [[ "$FRESH" -eq 1 ]]; then
    echo "卸载旧包（--fresh）"
    "$ADB" -s "$SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true
fi

echo "安装 $APK"
if ! INSTALL_OUT="$("$ADB" -s "$SERIAL" install -r "$APK" 2>&1)"; then
    printf '%s\n' "$INSTALL_OUT" >&2
    if printf '%s' "$INSTALL_OUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
        echo "签名不一致：debug 与 release 不能互相覆盖，请加 --fresh 后重试。" >&2
    fi
    exit 1
fi
printf '%s\n' "$INSTALL_OUT" | tail -1

VERSION="$("$ADB" -s "$SERIAL" shell dumpsys package "$PACKAGE" 2>/dev/null \
    | grep -m1 versionName | tr -d '\r' | awk -F= '{print $2}' || true)"
echo "启动 ${ACTIVITY}（版本 ${VERSION:-未知}）"
"$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY" | grep -E "Status|TotalTime" || true

echo
if [[ "$CAR" -eq 1 ]]; then
    echo "就绪：${AVD} / ${SERIAL} / ${MODE} / 车机 ${CAR_SIZE}@${CAR_DENSITY}dpi"
else
    echo "就绪：${AVD} / ${SERIAL} / ${MODE}"
fi
echo "恢复默认显示：./run.sh --reset --avd ${AVD}"
echo "查看日志：$ADB -s $SERIAL logcat"
