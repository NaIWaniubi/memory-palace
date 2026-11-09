#!/usr/bin/env bash
# 重拍演示视频素材（干净版 + 状态校验）。
#
# 两个坑，本脚本各有一条对策：
#
# 坑 1 · 系统 ANR 弹窗遮画面
#   模拟器在内存压力下反复弹「XX 没有响应」（应用 ANR + SpatialSystemUI ANR）。
#   实测：input tap 无效、DPAD 只对应用自身 ANR 有效。
#   对策：`settings put global hide_error_dialogs 1` 阻止新弹窗 +
#         `am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS` 清掉已有的。
#
# 坑 2 · 抓帧时动作还没生效（状态错位）
#   固定 sleep 不可靠 —— 模拟器慢，各动作耗时不同，抓到的画面和预期场景对不上。
#   对策：每个动作都有 logcat 输出作为确认点，等到那条日志出现再抓帧
#         （wait_log），而不是盲等固定秒数。
#
# 产出：media/shots/<name>.jpg
# 用法：bash scripts/reshoot.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/media/shots"
DEVICE="$(adb devices | awk 'NR==2{print $1}')"

if [ -z "${DEVICE:-}" ]; then
  echo "找不到模拟器设备" >&2
  exit 1
fi

mkdir -p "$OUT"
sh_() { MSYS_NO_PATHCONV=1 command adb -s "$DEVICE" shell "$@"; }

# 禁掉系统错误弹窗
sh_ settings put global hide_error_dialogs 1 >/dev/null 2>&1
sh_ am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1

dismiss_anr() {
  sh_ am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
}

# 等某条 logcat 出现（最多 $2 秒）。$1=正则 $2=超时秒
# 按 tag 过滤而不是全量 dump —— 全量 dump 在模拟器上要好几秒，轮询会把脚本拖死
wait_log() {
  local pattern="$1" timeout="${2:-60}" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if command adb -s "$DEVICE" logcat -d \
         -s DualState:V Anchor:V Review:V Clock:V Import:V DataGraph:V SpatialCue:V \
         2>/dev/null | grep -qE "$pattern"; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "    ⚠ 等日志超时：$pattern"
  return 1
}

grab() {
  local name="$1"
  dismiss_anr
  sh_ screencap -p /sdcard/_shot.png
  local dst="$OUT/$name.jpg"
  local tmp="$OUT/_raw.png"
  local dst_win="$(cygpath -w "$dst" 2>/dev/null || echo "$dst")"
  local tmp_win="$(cygpath -w "$tmp" 2>/dev/null || echo "$tmp")"
  MSYS_NO_PATHCONV=1 command adb -s "$DEVICE" pull /sdcard/_shot.png "$tmp_win" >/dev/null
  # 必须先写临时文件再转 —— 输入输出同路径 ffmpeg 会直接报错
  ffmpeg -y -i "$tmp_win" -vf "scale=1600:1600:force_original_aspect_ratio=decrease" -q:v 3 "$dst_win" 2>/dev/null || {
    echo "    ⚠ ffmpeg 转换失败：$name"
    rm -f "$tmp"
    return 1
  }
  rm -f "$tmp"
  echo "  · $name.jpg"
}

echo "· 启动应用"
command adb -s "$DEVICE" logcat -c
sh_ am force-stop com.memorypalace
sleep 3
sh_ am start -n com.memorypalace/.app.LaunchActivity >/dev/null

echo "· 等三窗就绪"
wait_log "autoOpenOnce: windows opened" 180 || true
# 窗口日志出来后还要等渲染（模拟器 GL 首帧慢）
sleep 8
dismiss_anr
grab v01_coldstart_3win

echo "· 进入房间层"
sh_ am broadcast -a com.memorypalace.dualstate.OPEN_STAGE >/dev/null
wait_log "openStage result=" 90 || true
sleep 10
grab v02_full_space

echo "· 放置第一张锚定卡"
sh_ am broadcast -a com.memorypalace.anchor.PLACE \
  --ef x 0.0 --ef y 1.2 --ef z -1.5 \
  --es front "serendipity" --es back "n.意外之喜" >/dev/null
wait_log "record persisted key=" 60 || true
sleep 6
grab v03_anchor_1

echo "· 放置第二张锚定卡"
sh_ am broadcast -a com.memorypalace.anchor.PLACE \
  --ef x 0.8 --ef y 1.3 --ef z -1.2 \
  --es front "ephemeral" --es back "adj.转瞬即逝的" >/dev/null
wait_log "total=5|total=6|total=7|total=8" 60 || true
sleep 8
grab v04_anchor_2

echo "· 回共享空间看队列"
sh_ am broadcast -a com.memorypalace.dualstate.CLOSE_STAGE >/dev/null
sleep 10
grab v05_due_queue

echo "· 复习：翻面"
sh_ am broadcast -a com.memorypalace.review.START >/dev/null
wait_log "start:.*card\(s\) due" 60 || true
sleep 4
sh_ am broadcast -a com.memorypalace.review.REVEAL >/dev/null
wait_log "reveal:" 40 || true
sleep 4
grab v06_review_front

echo "· 复习：自评"
sh_ am broadcast -a com.memorypalace.review.GRADE --es grade remember >/dev/null
wait_log "graded:" 40 || true
sleep 5
grab v07_review_graded

echo "· 时间机器 +7 天（面板）"
sh_ am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7 >/dev/null
wait_log "offset set to 7d" 40 || true
sleep 8
grab v08_timemachine_panel

echo "· 时间机器（房间层）"
sh_ am broadcast -a com.memorypalace.dualstate.OPEN_STAGE >/dev/null
wait_log "openStage result=Allowed" 90 || true
sleep 12
grab v09_faded_room

echo "· 复位"
sh_ am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 0 >/dev/null
wait_log "offset set to 0d" 40 || true
sleep 8
grab v10_reset

echo "· 导入确认页"
sh_ am broadcast -a com.memorypalace.dualstate.CLOSE_STAGE >/dev/null
sleep 8
sh_ am broadcast -a com.memorypalace.card.PREVIEW --es path /sdcard/mp-video/demo.csv >/dev/null
wait_log "preview ready:" 60 || true
sleep 5
grab v11_import_confirm

echo "· 确认入库"
sh_ am broadcast -a com.memorypalace.card.CONFIRM >/dev/null
wait_log "confirm import done" 60 || true
sleep 5
grab v12_after_confirm

echo
echo "重拍完成：$(ls "$OUT"/*.jpg 2>/dev/null | wc -l) 张"
