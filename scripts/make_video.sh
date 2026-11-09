#!/usr/bin/env bash
# 用重拍的干净截图合成演示视频（Ken Burns 动效 + 字幕 + 角标）。
#
# 素材来源：media/shots/（scripts/reshoot.sh 产出，已关闭系统弹窗、按日志确认状态）
#
# 为什么不用实时录屏（两条路都实测失败）：
#   ① screenrecord：MR 透视画面走硬件合成层，录出来是纯黑
#   ② 逐帧 screencap 合成：模拟器 GPU 在内存压力下随机半黑（连续 5 帧里第 3 帧下半全黑）
# 两条都是模拟器独有问题，真机不受影响。截图路线素材真实、零渲染风险、可复现。
#
# 字体：本机没有 Microsoft YaHei，中文用 Noto Sans SC（已复制到无空格路径，
#      带空格的字体路径在 Git Bash 里会被截断，drawtext 会渲染成方框）。
#
# 用法：bash scripts/make_video.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHOTS="$ROOT/media/shots"
WORK="$ROOT/media/work"
FONT="$WORK/fonts/NotoSansSC.otf"
FINAL="$ROOT/media/demo-video.mp4"

command -v ffmpeg >/dev/null 2>&1 || { echo "需要 ffmpeg" >&2; exit 1; }
[ -f "$FONT" ] || { echo "缺字体：$FONT（从 C:/Windows/Fonts 复制 Noto Sans SC）" >&2; exit 1; }

mkdir -p "$WORK/seg"
rm -f "$WORK"/seg/*.mp4

# 幕：文件名 | 字幕 | 秒
SCENES=(
"v01_coldstart_3win|记忆宫殿 · 把你的房间变成记忆宫殿|8"
"v02_full_space|三窗同开：书房看板 · 随身复习卡 · 房间层|10"
"v03_anchor_1|把卡片钉在真实家具上|10"
"v04_anchor_2|每个位置本身就是记忆线索|10"
"v05_due_queue|遗忘曲线算好的今日到期队列|12"
"v06_review_front|走到卡片前，翻面自评|12"
"v07_review_graded|记得 / 忘了 —— 算法重新排期|10"
"v08_timemachine_panel|时间机器：一键快进七天|12"
"v09_faded_room|到期卡片肉眼可见地褪色|14"
"v10_reset|复习把它们找回来|10"
"v11_import_confirm|导入 Anki apkg：字段映射确认页|12"
"v12_after_confirm|确认入库，队列立即变化|12"
)

# 校验素材
for e in "${SCENES[@]}"; do
  f="${e%%|*}"
  [ -f "$SHOTS/$f.jpg" ] || { echo "缺素材：$SHOTS/$f.jpg" >&2; exit 1; }
done

SUB="$WORK/subs.ass"
# 传给 ffmpeg 滤镜的路径必须是 Windows 风格（E:/abc/...）。
# pwd 在 Git Bash 下是 MSYS 风格 /e/abc/...，原生 ffmpeg 不认，会报 No such file。
# 所有 ffmpeg 调用都在 $WORK 目录下执行，用相对路径彻底避开路径里的冒号
# （ffmpeg 滤镜参数里 : 是选项分隔符，绝对路径的盘符必须转义，容易踩坑）
FONT_FF="fonts/NotoSansSC.otf"

# ASS 样式里的字体名用 Noto Sans SC（本机已装，libass 走 DirectWrite 能找到）
cat > "$SUB" <<'ASSHEAD'
[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1920
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Noto Sans SC,54,&H00FFFFFF,&H00FFFFFF,&H00000000,&H78000000,-1,0,0,0,100,100,0,0,1,4,2,2,90,90,80,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
ASSHEAD

ts() { printf "%d:%02d:%02d" $(( $1 / 3600 )) $(( ($1 % 3600) / 60 )) $(( $1 % 60 )); }

start=0
for e in "${SCENES[@]}"; do
  rest="${e#*|}"; dur="${rest##*|}"; sub="${rest%%|*}"
  end=$((start + dur))
  printf 'Dialogue: 0,%s,%s,Default,,0,0,0,,%s\n' "$(ts $start)" "$(ts $end)" "$sub" >> "$SUB"
  start=$end
done
TOTAL_BODY=$start

echo "· 正片 ${TOTAL_BODY}s，${#SCENES[@]} 幕"

SHOTS_WIN="$(cygpath -w "$SHOTS")"
cd "$WORK"

echo "· 生成分幕片段（Ken Burns 慢推 + 字幕直接烧进每段）"
n=1
LIST="$WORK/list.txt"
: > "$LIST"
for e in "${SCENES[@]}"; do
  f="${e%%|*}"; rest="${e#*|}"
  dur="${rest##*|}"; sub="${rest%%|*}"
  echo "  · [$n] $f (${dur}s)"
  frames=$((dur * 30))
  # 字幕用 drawtext 直接烧进每一段 —— libass 的 ASS 字幕在本机解析不出中文字体
  # （样式里写的字体名 libass 找不到，整条字幕静默消失），drawtext 用显式
  # fontfile 才可靠。
  vf="scale=2400:2400:force_original_aspect_ratio=increase,crop=2400:2400,zoompan=z='min(zoom+0.00028,1.10)':d=$frames:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1920x1920:fps=30,drawtext=fontfile='$FONT_FF':text='$sub':fontcolor=white:fontsize=52:borderw=4:bordercolor=black@0.85:x=(w-text_w)/2:y=h-text_h-110"
  ffmpeg -y -loop 1 -i "$SHOTS_WIN/$f.jpg" -filter_complex "$vf" -t "$dur" -r 30 \
    -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p "seg/$(printf '%02d' $n).mp4" 2>/dev/null
  printf "file '%s'\n" "$(cygpath -w "$WORK/seg/$(printf '%02d' $n).mp4")" >> "$LIST"
  n=$((n + 1))
done

# 片头（黑底标题）
echo "· 片头"
ffmpeg -y -f lavfi -i "color=c=0x12141A:s=1920x1920:d=6:r=30" \
  -vf "drawtext=fontfile='$FONT_FF':text='记忆宫殿':fontcolor=white:fontsize=120:x=(w-text_w)/2:y=(h-text_h)/2-70,drawtext=fontfile='$FONT_FF':text='Memory Palace · PICO MR':fontcolor=0x9AA3B2:fontsize=44:x=(w-text_w)/2:y=(h-text_h)/2+70" \
  -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p "seg/00.mp4" 2>/dev/null

# 片尾（黑底 + 仓库）
echo "· 片尾"
ffmpeg -y -f lavfi -i "color=c=0x12141A:s=1920x1920:d=8:r=30" \
  -vf "drawtext=fontfile='$FONT_FF':text='把知识，钉在空间里':fontcolor=white:fontsize=76:x=(w-text_w)/2:y=(h-text_h)/2-60,drawtext=fontfile='$FONT_FF':text='github.com/NaIWaniubi/memory-palace':fontcolor=0x7CD4FD:fontsize=40:x=(w-text_w)/2:y=(h-text_h)/2+60,drawtext=fontfile='$FONT_FF':text='Apache-2.0 · 开源':fontcolor=0x9AA3B2:fontsize=32:x=(w-text_w)/2:y=(h-text_h)/2+130" \
  -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p "seg/99.mp4" 2>/dev/null

echo "· 拼接"
FULL="$WORK/full_list.txt"
{
  printf "file '%s'\n" "$(cygpath -w "$WORK/seg/00.mp4" 2>/dev/null || echo "$WORK/seg/00.mp4")"
  cat "$LIST"
  printf "file '%s'\n" "$(cygpath -w "$WORK/seg/99.mp4" 2>/dev/null || echo "$WORK/seg/99.mp4")"
} > "$FULL"
ffmpeg -y -f concat -safe 0 -i "$FULL" -c copy "_concat.mp4" 2>/dev/null

echo "· 叠角标（右上角「模拟器录制」）"
ffmpeg -y -i "_concat.mp4" \
  -vf "drawbox=x=1330:y=46:w=530:h=70:color=black@0.55:t=fill,drawtext=fontfile='$FONT_FF':text='模拟器录制':fontcolor=white@0.95:fontsize=38:x=1360:y=60" \
  -c:v libx264 -preset medium -crf 22 -pix_fmt yuv420p -movflags +faststart "$(cygpath -w "$FINAL")" 2>&1 | tail -2

EXPECTED=$((TOTAL_BODY + 14))
echo
echo "完成：$FINAL（预期 ${EXPECTED}s）"
ls -la "$FINAL"
ffprobe -v error -show_entries format=duration,size -show_entries stream=width,height,r_frame_rate \
  -of default=noprint_wrappers=1 "$FINAL" 2>&1 | head -8
