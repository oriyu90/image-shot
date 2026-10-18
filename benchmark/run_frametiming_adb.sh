#!/usr/bin/env zsh
# P2: フレーム計測（gfxinfo 方式）— Macrobenchmark FrameTimingMetric が当端末で
# 0 フレームのための代替（@Ignore 記録済み）。
# 使い方: benchmark/run_frametiming_adb.sh [DEVICE] [DRAG_ROUNDS]
set -u
DEVICE=${1:-HA2EPG4J}
ROUNDS=${2:-8}

# 状態: ホームから起動しライブラリ→エディタ(test_photo.jpg)、露出スライダー行を左右ドラッグ
adb -s HA2EPG4J shell input keyevent KEYCODE_HOME; sleep 1
adb -s HA2EPG4J shell am start -n com.imageshot.app/.MainActivity; sleep 4
adb -s HA2EPG4J shell input tap 159 526; sleep 5

# gfxinfo リセット
adb -s HA2EPG4J shell dumpsys gfxinfo com.imageshot.app reset >/dev/null

Y=$((1420))
for i in $(seq 1 $ROUNDS); do
  adb -s HA2EPG4J shell input swipe 533 $Y 2880 $Y 300
  adb -s HA2EPG4J shell input swipe 2880 $Y 533 $Y 300
done
sleep 2

adb -s HA2EPG4J shell dumpsys gfxinfo com.imageshot.app | grep -E "Total frames rendered|Janky frames|percentile|Missed Vsync|deadline missed" | head -12
echo "条件: TB710FU API36 / app buildType=benchmark(非debuggable,profileable) / ドラッグ回数=$ROUNDS / $(date '+%Y-%m-%d %H:%M')"
