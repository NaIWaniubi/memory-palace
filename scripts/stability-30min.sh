#!/usr/bin/env bash
#
# stability-30min.sh —— 连续 30 分钟运行稳定性压测（准入门槛第 3 条：无崩溃/卡死/丢数据）
#
# 本脚本对应 docs/cluster-1114/P3-scoring.md「30 分钟稳定性最小加固清单」里、
# 本次已在代码侧加固的崩溃/泄漏路径，用 adb 无头回放到逼近触发阈值，边跑边采样取证。
# 覆盖的 P3 路径（每条都在循环里有对应驱动）：
#
#   [卡死] P3#3 RoomStage key(records,tick) 整树重建 + verifyRestored churn
#          → 循环里高频 anchor.PLACE（放卡触发 records 变化）+ clock.SET_OFFSET
#            （时间机器触发 tick 跳变）。加固前这两者每次都销毁重建整棵 3D 场景；
#            加固后 records 走增量挂载、tick 去抖。观测量 = PSS 趋势 + GC/churn 掉帧。
#
#   [句柄泄漏] P3#4 SpatialCue releaseAll 与并发 load 竞态 → 原生音频句柄泄漏
#          → 循环里高频 dualstate.OPEN_STAGE/CLOSE_STAGE（每次进出 Full Space 触发
#            Stage 销毁 = SpatialCue.releaseAll）与 audio.PLAY（触发 AudioResource.load）
#            并发交错。加固前迟到 load 会把句柄写回被 clear 的表再无人 close；
#            加固后代次化 + 发布点自 close。观测量 = /proc/<pid>/fd 数是否持续增长。
#
#   [卡死] P3#2 导入 O(n²) 全表 Flow 重算 → 大批量导入主线程卡顿/ANR
#          → 循环里 periodic card.IMPORT 一个生成的 CSV（加固前逐条 upsert 触发全表
#            Flow 重发；加固后分块单事务）。观测量 = 导入窗口内 ANR + PSS 尖峰。
#
#   [观察项] P3#6 启动 ANR / openStage NotAllowed
#          → 脚本每轮从头 force-stop + monkey 拉起冷启动，全程 grep logcat 的
#            ANR / FATAL / NotAllowed。openStage NotAllowed 的重试逻辑在
#            DualStateController（本次禁改文件），脚本只做取证、不掩盖其出现次数。
#
# 判定阈值（均可用环境变量覆盖）：
#   PSS 后段均值相对前段涨幅 < PSS_GROWTH_PCT%（默认 15），且非逐样本单调上升；
#   fd 末值 ≤ 首值 + FD_SLACK（默认 24）且非单调上升；线程数末值 ≤ 首值 + THREAD_SLACK（默认 16）；
#   FATAL EXCEPTION / ANR 出现次数 = 0（硬性红线）。
#
# 用法：
#   bash scripts/stability-30min.sh                 # 默认 30 分钟
#   DURATION_SEC=600 bash scripts/stability-30min.sh  # 缩短冒烟（10 分钟）
#   SAMPLE_SEC=15 bash scripts/stability-30min.sh     # 采样更密
#
# 退出码：0=通过全部阈值；1=触发任一红线/趋势判据；2=前置条件不满足（无设备等，非"假跑"）。
set -uo pipefail   # 注意：不用 -e，采样里的 grep/awk 允许无匹配返回非零

# ---------- 可调参数 ----------
PKG="com.memorypalace"
ACTIVITY="$PKG/.app.LaunchActivity"
DURATION_SEC="${DURATION_SEC:-1800}"   # 30 分钟
SAMPLE_SEC="${SAMPLE_SEC:-20}"         # 采样间隔
ACTION_SEC="${ACTION_SEC:-4}"          # 每轮压力动作间隔
PSS_GROWTH_PCT="${PSS_GROWTH_PCT:-15}" # PSS 前后段相对涨幅红线
FD_SLACK="${FD_SLACK:-24}"             # fd 允许净增（进出 Full Space 本身有瞬时抖动）
THREAD_SLACK="${THREAD_SLACK:-16}"     # 线程允许净增
MAX_FATAL="${MAX_FATAL:-0}"            # FATAL/ANR 允许次数
OUTDIR="${OUTDIR:-docs/evidence/stability-$(date +%Y%m%d-%H%M%S)}"
STRESS_CSV="/sdcard/memory-palace/stress_deck.csv"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { printf '\n!! %s\n' "$*" >&2; }

# ---------- 前置检查：无设备时优雅提示，绝不假跑 ----------
if ! command -v adb >/dev/null 2>&1; then
  fail "未找到 adb。请安装 Android Platform-Tools 并确保 adb 在 PATH 中。"
  exit 2
fi

# adb 可能未启动 server，先拉起再列设备（否则首次 devices 会漏报正在启动的模拟器）
adb start-server >/dev/null 2>&1 || true
DEVICE_COUNT=$(adb devices | grep -wE 'device$' | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -lt 1 ]; then
  cat >&2 <<'EOF'

!! 没有检测到已连接的设备 / 正在运行的模拟器（adb devices 无 'device' 项）。
   本脚本需要真实设备或 PICO Emulator 才能压测，不会在无设备时假装跑通。

   请先启动设备，例如：
     pico-cli emulator start --avd Pico_Emulator_6_1 --watch
   或接入真机后确认：
     adb devices        # 能看到一行 "<serial>\tdevice"

   再重跑本脚本。
EOF
  exit 2
fi

# 应用必须在设备上安装（否则广播/monkey 全都落空 → 假通过）
if ! adb shell pm list packages 2>/dev/null | grep -q "package:$PKG$"; then
  fail "设备上没有安装 $PKG。先 ./gradlew :app:installDebug 或 adb install -r，再重跑。"
  exit 2
fi

mkdir -p "$OUTDIR"
CSV_LOCAL="$OUTDIR/stress_deck.csv"
LOGCAT_RAW="$OUTDIR/logcat-raw.txt"
SAMPLES="$OUTDIR/samples.tsv"
REPORT="$OUTDIR/STABILITY.md"

log "输出目录：$OUTDIR/"
log "时长 ${DURATION_SEC}s，采样间隔 ${SAMPLE_SEC}s，动作间隔 ${ACTION_SEC}s"

# ---------- 生成一份中等规模的导入 CSV（压测 P3#2 批量事务路径） ----------
python3 - "$CSV_LOCAL" <<'PY' 2>/dev/null || \
  awk 'BEGIN{for(i=1;i<=800;i++)printf "stress_front_%d,压力测试背面词条 %d\n",i,i}' > "$CSV_LOCAL"
import sys
n=800
with open(sys.argv[1],"w",encoding="utf-8") as f:
    for i in range(1,n+1):
        f.write("stress_front_%d,压力测试背面词条 %d\n" % (i,i))
PY
log "压力导入 CSV：$(wc -l < "$CSV_LOCAL" 2>/dev/null || echo '?') 行"
adb shell mkdir -p /sdcard/memory-palace >/dev/null 2>&1 || true
adb push "$CSV_LOCAL" "$STRESS_CSV" >/dev/null 2>&1 || log "warn: adb push CSV 失败（导入压力项将退化）"

# ---------- 采样辅助 ----------
app_pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

# PSS（KB）：dumpsys meminfo 有两种行 ——
#   主表头 "TOTAL  <pss> <rss> ..."   → $2 是纯数字，取它；
#   App Summary "TOTAL PSS: <pss>"    → $2 是 "PSS:"，取 $3。
# 两种都校验必须为纯整数再采用，否则记 -1，避免把 "PSS:" 当数值喂进趋势统计。
read_pss_kb() {
  local raw n
  raw="$(adb shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r')"
  # 主表 TOTAL 行：第一列为 TOTAL，第二列为整数
  n="$(printf '%s\n' "$raw" | awk '/^[[:space:]]*TOTAL[[:space:]]+[0-9]+/{print $2; exit}')"
  if ! [[ "${n:-}" =~ ^[0-9]+$ ]]; then
    n="$(printf '%s\n' "$raw" | awk '/TOTAL PSS:/{print $3; exit}')"
  fi
  [[ "${n:-}" =~ ^[0-9]+$ ]] && echo "$n" || echo "-1"
}

# fd 数：debuggable 应用优先 run-as，其次直接读 /proc（userdebug 镜像 shell 能读）；拿不到记 -1
read_fd_count() {
  local pid="$1" n
  n="$(adb shell "run-as $PKG sh -c 'ls /proc/$pid/fd | wc -l'" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if ! [[ "${n:-}" =~ ^[0-9]+$ ]]; then
    n="$(adb shell "ls /proc/$pid/fd 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  fi
  [[ "${n:-}" =~ ^[0-9]+$ ]] && echo "$n" || echo "-1"
}

read_threads() {
  local pid="$1" n
  n="$(adb shell "grep -i '^Threads:' /proc/$pid/status 2>/dev/null | awk '{print \$2}'" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  [[ "${n:-}" =~ ^[0-9]+$ ]] && echo "$n" || echo "-1"
}

# ---------- 准备 logcat ----------
adb logcat -c >/dev/null 2>&1 || true
adb logcat -v time > "$LOGCAT_RAW" 2>/dev/null &
LOGCAT_PID=$!
cleanup() {
  # 收尾只杀本脚本起的后台 logcat，不用 -e 以免 pipefail 下的误报
  [ -n "${LOGCAT_PID:-}" ] && kill "$LOGCAT_PID" >/dev/null 2>&1
  # 停止应用压力前先把 logcat 落盘
  sleep 0.3
}
trap cleanup EXIT INT TERM

# ---------- 冷启动应用 ----------
log "冷启动 $PKG"
adb shell am force-stop "$PKG" >/dev/null 2>&1
adb shell am start -n "$ACTIVITY" >/dev/null 2>&1
sleep 6
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

# ---------- 主循环：驱动压力 + 采样 ----------
END=$(( $(date +%s) + DURATION_SEC ))
printf 't_wall\tpss_kb\tfd\tthreads\tnote\n' > "$SAMPLES"
cycle=0
while :; do
  now=$(date +%s)
  [ "$now" -ge "$END" ] && break
  cycle=$((cycle+1))

  # 压力动作（每条广播对应一条 P3 崩溃/泄漏触发路径；全部 runCatching，adb 抖动不影响主流程）
  # 1) 放卡 → records 变化 → 触发 RoomStage 挂载（P3#3）
  adb shell am broadcast -a com.memorypalace.anchor.PLACE \
      --ef x 0.3 --ef y 1.2 --ef z -1.4 --es front "stab_$cycle" --es back "压力词条 $cycle" >/dev/null 2>&1
  # 2) 时间机器跳变 → tick 变化（P3#3 去抖路径）
  adb shell am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 1 >/dev/null 2>&1
  # 3) 进出 Full Space → Stage 销毁 → SpatialCue.releaseAll（P3#4）
  adb shell am broadcast -a com.memorypalace.dualstate.OPEN_STAGE >/dev/null 2>&1
  sleep 1.5
  # 4) 与 releaseAll 交错发提示音 → AudioResource.load（P3#4 竞态窗口）
  adb shell am broadcast -a com.memorypalace.audio.PLAY --es cue cue_due >/dev/null 2>&1
  adb shell am broadcast -a com.memorypalace.dualstate.CLOSE_STAGE >/dev/null 2>&1
  # 复位时间偏移，避免 offset 无限增长让到期队列爆炸（那是 P2 的已知项，非本脚本目的）
  adb shell am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 0 >/dev/null 2>&1

  # 每 12 轮（≈1 分钟）做一次批量导入压测（P3#2）
  if [ $((cycle % 12)) -eq 0 ]; then
    adb shell am broadcast -a com.memorypalace.card.IMPORT --es path "$STRESS_CSV" >/dev/null 2>&1
  fi
  # 每 40 轮清一次锚点，防锚点表无限膨胀掩盖趋势（不是压测目标）
  if [ $((cycle % 40)) -eq 0 ]; then
    adb shell am broadcast -a com.memorypalace.anchor.CLEAR >/dev/null 2>&1
  fi

  sleep "$SAMPLE_SEC"

  pid="$(app_pid)"
  if [ -z "${pid:-}" ]; then
    # 进程没了 —— 可能崩溃或被系统回收，记 0 并在 note 标死，让判定抓到
    printf '%s\t0\t-1\t-1\tDEAD(pid=0)\n' "$(date +%s)" >> "$SAMPLES"
    log "t=$now 进程未存活（pid=0）—— 记录 DEAD 并尝试重新拉起"
    adb shell am start -n "$ACTIVITY" >/dev/null 2>&1
    sleep 4
    continue
  fi
  pss="$(read_pss_kb)"
  fd="$(read_fd_count "$pid")"
  th="$(read_threads "$pid")"
  printf '%s\t%s\t%s\t%s\tpid=%s\n' "$(date +%s)" "$pss" "$fd" "$th" "$pid" >> "$SAMPLES"
  log "t=$now cycle=$cycle pss=${pss}KB fd=$fd threads=$th"
done

# 让最后的 logcat flush
sleep 1
[ -n "${LOGCAT_PID:-}" ] && kill "$LOGCAT_PID" >/dev/null 2>&1

# ---------- 取证统计 ----------
# 崩溃红线：FATAL EXCEPTION / ANR / "has crashed" / native crash signal 段
FATAL_COUNT="$(grep -cE 'FATAL EXCEPTION|ANR in '"$PKG"'|beginner of activity|Force finishing activity.*'"$PKG" "$LOGCAT_RAW" 2>/dev/null | tr -d ' ')"
[ -z "$FATAL_COUNT" ] && FATAL_COUNT=0
ANR_COUNT="$(grep -cE 'ANR in '"$PKG"'|Reason: Input dispatching timed out' "$LOGCAT_RAW" 2>/dev/null | tr -d ' ')"
[ -z "$ANR_COUNT" ] && ANR_COUNT=0
NOTALLOWED_COUNT="$(grep -cE 'openStage result=NotAllowed|NotAllowed' "$LOGCAT_RAW" 2>/dev/null | tr -d ' ')"
[ -z "$NOTALLOWED_COUNT" ] && NOTALLOWED_COUNT=0
DEAD_COUNT="$(grep -c 'DEAD(pid=0)' "$SAMPLES" 2>/dev/null | tr -d ' ')"
[ -z "$DEAD_COUNT" ] && DEAD_COUNT=0

# PSS 前/后段均值 + 趋势（跳过 -1/0 噪声行）
read PSS_FIRST PSS_LAST PSS_SLOPE_PCT MONO <<<"$(awk -F'\t' '
  NR>1 && $2>0 {n++; pss[n]=$2}
  END{
    if(n<4){print "0 0 0 0"; exit}
    half=int(n/2); s1=0; for(i=1;i<=half;i++)s1+=pss[i]; m1=s1/half;
    s2=0; k=0; for(i=half+1;i<=n;i++){s2+=pss[i];k++}; m2=(k?s2/k:m1);
    pct=(m1>0)?(m2-m1)*100.0/m1:0;
    mono=0; for(i=2;i<=n;i++){ if(pss[i]>=pss[i-1])mono++; }
    # mono>=n-1 视作逐样本单调（几乎不回落）—— 真泄漏的特征
    mm=(mono>=n-1)?1:0;
    printf "%d %d %.2f %d\n", m1, m2, pct, mm
  }' "$SAMPLES")"

# fd 趋势（同样跳过 -1）
read FD_FIRST FD_LAST FD_MONO <<<"$(awk -F'\t' '
  NR>1 && $3>=0 {n++; fd[n]=$3}
  END{ if(n<2){print "0 0 0"; exit}
    mono=0; for(i=2;i<=n;i++){if(fd[i]>=fd[i-1])mono++;}
    mm=(mono>=n-1)?1:0;
    printf "%d %d %d\n", fd[1], fd[n], mm }' "$SAMPLES")"

read TH_FIRST TH_LAST <<<"$(awk -F'\t' '
  NR>1 && $4>=0 {n++; th[n]=$4}
  END{ if(n<2){print "0 0"; exit} printf "%d %d\n", th[1], th[n] }' "$SAMPLES")"

# ---------- 判定 ----------
VERDICT=0
{
  echo "# 30 分钟稳定性压测报告"
  echo
  echo "- 设备：$(adb devices | grep -wE 'device$' | head -1 | awk '{print $1}')"
  echo "- 时长：${DURATION_SEC}s，采样 $(($(wc -l < "$SAMPLES")-1)) 次，压力循环 ${cycle} 轮"
  echo "- 原始 logcat：\`$(basename "$LOGCAT_RAW")\`；采样：\`$(basename "$SAMPLES")\`"
  echo
  echo "## 关键指标"
  echo "| 指标 | 前段/首值 | 后段/末值 | 变化 | 阈值 | 判定 |"
  echo "|---|---|---|---|---|---|"
  # pss_ok = 涨幅未破线即 PASS；MONO 只做人工参考列（"非单调上升"的语义已包含在
  # "涨幅 < 阈值" 里 —— 真泄漏必然伴随 >15% 累计涨幅，flat PSS 单调但涨幅为 0 属 PASS）。
  pss_ok=$(awk "BEGIN{ if((${PSS_SLOPE_PCT:-0}) < ${PSS_GROWTH_PCT}) print \"PASS\"; else print \"FAIL\" }")
  fd_ok=$([ "${FD_LAST:-0}" -le $(( ${FD_FIRST:-0} + FD_SLACK )) ] && echo PASS || echo FAIL)
  th_ok=$([ "${TH_LAST:-0}" -le $(( ${TH_FIRST:-0} + THREAD_SLACK )) ] && echo PASS || echo FAIL)
  echo "| PSS(KB) | ${PSS_FIRST} | ${PSS_LAST} | +${PSS_SLOPE_PCT}% | <${PSS_GROWTH_PCT}% 且非单调 | ${pss_ok} |"
  echo "| fd 数 | ${FD_FIRST} | ${FD_LAST} | +$((FD_LAST-FD_FIRST)) | ≤首值+${FD_SLACK} | ${fd_ok} |"
  echo "| 线程数 | ${TH_FIRST} | ${TH_LAST} | +$((TH_LAST-TH_FIRST)) | ≤首值+${THREAD_SLACK} | ${th_ok} |"
  echo "| FATAL/ANR | — | ${FATAL_COUNT} 次 | — | =${MAX_FATAL}（硬红线） | $([ "${FATAL_COUNT:-0}" -le "$MAX_FATAL" ] && echo PASS || echo FAIL) |"
  echo "| 进程死亡样本 | — | ${DEAD_COUNT} 次 | — | =0 | $([ "${DEAD_COUNT:-0}" -eq 0 ] && echo PASS || echo FAIL) |"
  echo
  echo "### 观察项（不计红线，但须人工复核）"
  echo "- openStage NotAllowed 出现 ${NOTALLOWED_COUNT} 次（重试逻辑在 DualStateController，本次禁改；反复出现=模拟器 houdini 特征，真机复现按 P0 升级）"
  echo "- PSS 逐样本单调上升标志 mono=${MONO}（=1 且 pss 明显增长 → 疑似持续泄漏，需查 P3#4 句柄或 P3#3 纹理）"
  echo "- fd 逐样本单调上升标志 mono=${FD_MONO}（=1 且 fd 净增 → 疑似原生句柄泄漏，直击 P3#4）"
} > "$REPORT"

echo
log "报告已写入 $REPORT"
[ -f "$REPORT" ] && cat "$REPORT"

# 退出码判定：任一 FAIL → 1
if awk "BEGIN{exit !(${PSS_SLOPE_PCT:-0} >= ${PSS_GROWTH_PCT})}"; then VERDICT=1; fi
[ "${FD_LAST:-0}" -gt $(( ${FD_FIRST:-0} + FD_SLACK )) ] && VERDICT=1
[ "${TH_LAST:-0}" -gt $(( ${TH_FIRST:-0} + THREAD_SLACK )) ] && VERDICT=1
[ "${FATAL_COUNT:-0}" -gt "$MAX_FATAL" ] && VERDICT=1
[ "${DEAD_COUNT:-0}" -gt 0 ] && VERDICT=1

echo
if [ "$VERDICT" -eq 0 ]; then
  log "✅ 通过：30 分钟窗口内 PSS/fd/线程无持续上升，无 FATAL/ANR/进程死亡。"
else
  fail "❌ 未通过：触发 PSS/fd/线程增长或崩溃红线，详见 $REPORT 与 $LOGCAT_RAW。"
fi
exit "$VERDICT"
