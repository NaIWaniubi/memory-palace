#!/usr/bin/env bash
# 录制演示视频（模拟器轨 A）—— 逐帧截图合成路线。
#
# 为什么不用 screenrecord：PICO Emulator 的 MR 透视画面走硬件合成层，
# `screenrecord` 只抓 SurfaceFlinger 的软件层，录出来是黑屏（实测确认）。
# 而 `screencap` 能抓到完整画面（MR 透视 + 三窗 + 事件日志都清晰）。
# 所以路线是：逐帧 screencap → ffmpeg 合成 mp4。
#
# 代价：不是实时录制，是「定时抓帧」。演示动作是广播驱动的状态跃迁，
# 抓帧只要覆盖跃迁前后就够 —— 每一段的画面在状态稳定后基本不变。
#
# 用法：bash scripts/record_demo.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/media/work"
FRAMES="$WORK/frames"
DEVICE="$(adb devices | awk 'NR==2{print $1}')"

if [ -z "${DEVICE:-}" ]; then
  echo "找不到模拟器设备。先跑：pico-cli emulator start --avd Pico_Emulator_6_1 --watch" >&2
  exit 1
fi

echo "设备：$DEVICE"
mkdir -p "$FRAMES"
rm -rf "$FRAMES"/*

sh_() { MSYS_NO_PATHCONV=1 command adb -s "$DEVICE" shell "$@"; }
pull_to() {
  local remote="$1" dest="$2"
  local win="$(cygpath -w "$dest" 2>/dev/null || echo "$dest")"
  MSYS_NO_PATHCONV=1 command adb -s "$DEVICE" pull "$remote" "$win" >/dev/null
}

# 抓一帧
grab() {
  local name="$1"
  sh_ screencap -p /sdcard/_frame.png
  pull_to /sdcard/_frame.png "$FRAMES/$name.png"
}

# 抓 N 帧，间隔 $2 秒（给状态跃迁留出稳定时间）
grab_series() {
  local prefix="$1" count="$2" interval="$3"
  for i in $(seq 1 "$count"); do
    grab "$(printf '%s_%02d' "$prefix" "$i")"
    sleep "$interval"
  done
}

# 关掉「XX 没有响应」的系统 ANR 弹窗。
# 模拟器走 houdini 转译，SDK 初始化在 onCreate 主线程超时会弹 ANR（真机无此问题）。
# `input tap` 对系统弹窗无效（和 3D 空间按钮一样），实测有效的是 DPAD 导航：
# DPAD_LEFT 移到「等待」按钮，DPAD_CENTER 触发它。「关闭应用」会杀应用，绝不能按到。
dismiss_anr() {
  sh_ uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1 || return 0
  if ! sh_ cat /sdcard/_ui.xml 2>/dev/null | grep -q "没有响应"; then
    return 0
  fi
  echo "  · 检测到 ANR 弹窗，用 DPAD 关闭"
  sh_ input keyevent DPAD_LEFT >/dev/null 2>&1
  sleep 1
  sh_ input keyevent DPAD_CENTER >/dev/null 2>&1
  sleep 2
}

# 等应用三窗就绪
wait_ready() {
  for i in $(seq 1 40); do
    if command adb -s "$DEVICE" logcat -d -s DualState:V 2>/dev/null | grep -q "autoOpenOnce: windows opened"; then
      echo "  · 三窗就绪"
      return 0
    fi
    sleep 3
  done
  echo "  · ⚠ 未检测到三窗就绪日志"
}

echo "· 启动应用"
sh_ am force-stop com.memorypalace
sleep 2
sh_ am start -n com.memorypalace/.app.LaunchActivity >/dev/null
wait_ready
dismiss_anr

echo "· 冷启动占位（三窗出现）"
grab_series s01_coldstart 3 1.5

echo "· 进入房间层"
sh_ am broadcast -a com.memorypalace.dualstate.OPEN_STAGE >/dev/null
sleep 12
dismiss_anr
grab_series s02_stage 3 1.5

echo "· 放置锚定卡片"
sh_ am broadcast -a com.memorypalace.anchor.PLACE \
  --ef x 0.0 --ef y 1.2 --ef z -1.5 \
  --es front "serendipity" --es back "n.意外之喜" >/dev/null
sleep 6
sh_ am broadcast -a com.memorypalace.anchor.PLACE \
  --ef x 0.8 --ef y 1.3 --ef z -1.2 \
  --es front "ephemeral" --es back "adj.转瞬即逝的" >/dev/null
sleep 8
dismiss_anr
grab_series s03_anchors 3 1.5

echo "· 回到共享空间看队列"
sh_ am broadcast -a com.memorypalace.dualstate.CLOSE_STAGE >/dev/null
sleep 8
dismiss_anr
grab_series s04_queue 3 1.5

echo "· 复习流"
sh_ am broadcast -a com.memorypalace.review.START >/dev/null
sleep 4
sh_ am broadcast -a com.memorypalace.review.REVEAL >/dev/null
sleep 3
grab_series s05_review 3 1.5

sh_ am broadcast -a com.memorypalace.review.GRADE --es grade remember >/dev/null
sleep 3
sh_ am broadcast -a com.memorypalace.review.REVEAL >/dev/null
sleep 3
sh_ am broadcast -a com.memorypalace.review.GRADE --es grade forgot >/dev/null
sleep 3
grab_series s06_graded 2 1.5

echo "· 时间机器（+7 天）"
sh_ am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7 >/dev/null
sleep 8
dismiss_anr
grab_series s07_timemachine 3 1.5
sh_ am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 0 >/dev/null
sleep 4

echo "· 空间音频方位引导"
sh_ am broadcast -a com.memorypalace.dualstate.OPEN_STAGE >/dev/null
sleep 10
dismiss_anr
sh_ am broadcast -a com.memorypalace.audio.PLAY --es cue cue_due >/dev/null
sleep 4
grab_series s08_spatial_audio 3 1.5

echo "· 导入流程"
sh_ am broadcast -a com.memorypalace.dualstate.CLOSE_STAGE >/dev/null
sleep 6
dismiss_anr
sh_ am broadcast -a com.memorypalace.card.PREVIEW --es path /sdcard/mp-video/demo.csv >/dev/null
sleep 5
grab_series s09_import 3 1.5

echo "· 到期提醒"
sh_ am broadcast -a com.memorypalace.notify.SCHEDULE >/dev/null
sleep 6
dismiss_anr
grab_series s10_reminder 2 1.5

echo
echo "抓帧完成：$(ls "$FRAMES"/*.png 2>/dev/null | wc -l) 帧"
