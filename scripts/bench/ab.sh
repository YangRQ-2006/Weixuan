#!/bin/sh
# 微玄端侧推理 A/B 基准工具
#
# 用法：
#   sh ab.sh --model <gguf> [--dev HTP0] [--rounds 3] [--ctx 2048] [--gen 96] \
#            --variant "基线" --variant "+ub1024:-ub 1024" ...
#
#   --variant 语法： "<标签>" 或 "<标签>:<追加给 llama-server 的参数>"
#   变体按 --rounds 轮**交替**执行（A,B,C,A,B,C...），把热漂移摊平到各方；
#   最后打印每个变体的 decode / prefill **中位数**。
#
# ⚠️ 方法学铁律（2026-10-02 血的教训，见 README.md）：
#   1) 请求体必须带 "cache_prompt": false，否则 KV 前缀复用会把 prompt_per_second
#      变成「几个 token 摊固定开销」的假数字（实测能把 1000 tok/s 显示成 155 tok/s，
#      也能把「+7% 收益」造出来）。
#   2) 每次测量前先发一次**同样条件**的请求当预热（编译 HTP 图），只取第二次的数字。
#   3) 必须交替执行、跑 ≥3 轮取中位数，否则 ±10% 的跑内方差会盖过真实差异。
set -eu

MODEL=""; DEV="HTP0"; ROUNDS=3; CTX=2048; GEN=96
LINES=""; LABELS=""; EXPTS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --model)   MODEL="$2"; shift 2 ;;
    --dev)     DEV="$2"; shift 2 ;;
    --rounds)  ROUNDS="$2"; shift 2 ;;
    --ctx)     CTX="$2"; shift 2 ;;
    --gen)     GEN="$2"; shift 2 ;;
    --variant)
      v="$2"; shift 2
      lab=${v%%:*}; ex=""
      case "$v" in *:*) ex=${v#*:} ;; esac
      LABELS="$LABELS|$lab"; EXPTS="$EXPTS|$ex"
      ;;
    --list)  exec sh -c 'ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1' ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

[ -n "$MODEL" ] || { echo "缺少 --model" >&2; exit 1; }
ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "找不到 nativeLibraryDir（装过微玄吗？）" >&2; exit 1; }

# ─────────────────────────────────────────────────────────────────────────────
# 预检：内存与温度（2026-10-02 手机失联事故后新增，硬性拦截）
# 事故经过：一轮基准（6~9 次 2.5GB 加载）跑完，移动网络失联、只剩紧急呼叫，重启恢复。
#          当时 MemAvailable 仅 5.83GB / MemTotal 15.37GB，CPU0 99.7°C。
# 这两道闸门是防止「跑一次就把手机搞挂」的最小代价。
# ─────────────────────────────────────────────────────────────────────────────
# ⚠️ 必须用 awk 算：Android 的 sh 是 **32 位有符号**整数运算，
# 2497281664 字节（2.4GB）会溢出成 -1797685632 → 除以 1048576 得 **-1714MB**
# → NEED_MB 变负数 → 内存闸门恒成立、形同虚设（2026-10-04 实测踩到）。awk 用 double。
MODEL_MB=$(stat -c %s "$MODEL" 2>/dev/null | awk '{printf "%d", $1/1048576}')
[ -n "$MODEL_MB" ] || MODEL_MB=0
NEED_MB=$(awk -v m="$MODEL_MB" 'BEGIN{printf "%d", m*1.2+1024}')
avail_mb() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
batt_temp() { dumpsys battery 2>/dev/null | awk -F': *' '/temperature/{print int($2/10)}'; }
# 停手线：双条件（2026-10-03 修正）—— 只看电池会误拦正常操作，危险的是 CPU 顶格 + 电池高。
# 代表温度 = sysfs 里 `cpu-*` 真实簇温传感器的最大值（2026-10-04 定案）。
#
# 为什么不用 dumpsys thermalservice 的 mType=0：本机温度上报有「双份 + 闩锁」问题，实测空闲时
#   第一组 CPU0..7 = 64.6 63.9 [99.7] 63.5 65.0 64.6 [99.6] 63.6   ← 含两个 99.7，物理上不可能
#   第二组 CPU0..7 = 34.2 33.8 34.2 34.2 34.2 34.2 32.9 33.3       ← 与 sysfs/电池自洽
#   电池也报两次：42.2 与 24.8
# → 取最大值会在空闲时判成过热（闸门永远拦死）；取中位数会落进两组之间的缝里；取第 3 高
#   也会被闩锁值击穿。而 sysfs 的 cpu-0-0-0 / cpu-0-0-1 / cpu-0-1-0 / cpu-0-2-0 = 35~36°C
#   与第二组、与电池温度全部自洽 —— 所以以 sysfs 为准。
# 同时排除 *trip* 类 zone（它们报的是跳闸阈值，本机 cpu-hw-trip-0 恒读 95）。
cpu_rep_c() {
  local m=0 v t
  for z in /sys/class/thermal/thermal_zone*; do
    [ -r "$z/type" ] || continue
    t=$(cat "$z/type" 2>/dev/null || echo "")
    case "$t" in *trip*) continue ;; esac      # ⚠️ 必须先排 trip：`cpu-hw-trip-0` 也匹配 cpu-*，它恒读 95（阈值不是温度）
    case "$t" in cpu-*) ;; *) continue ;; esac
    v=$(cat "$z/temp" 2>/dev/null || echo 0)
    v=$((v / 1000))
    [ "$v" -gt "$m" ] && m=$v
  done
  echo "$m"
}
OVERHEAT_LIMIT_C=42; CPU_LIMIT_C=85
overheat() {
  local b c; b=$(batt_temp); c=$(cpu_rep_c)
  [ "${b:-0}" -gt "$OVERHEAT_LIMIT_C" ] || [ "${c:-0}" -gt "$CPU_LIMIT_C" ]
}

AVAIL=$(avail_mb)
TEMP=$(batt_temp)
echo "[预检] 模型 ${MODEL_MB}MB，需 MemAvailable ≥ ${NEED_MB}MB；当前可用 ${AVAIL}MB，电池 ${TEMP}°C，CPU代表温 $(cpu_rep_c)°C"
if [ "${AVAIL:-0}" -lt "$NEED_MB" ]; then
  echo "⛔ 拒绝运行：可用内存 ${AVAIL}MB < 需求 ${NEED_MB}MB。先关掉后台 App / 等内存回收，不要硬上。" >&2
  exit 2
fi
if overheat; then
  echo "⛔ 拒绝运行：过热（电池 ${TEMP}°C / CPU $(cpu_rep_c)°C）。先让手机冷却。" >&2
  exit 3
fi
if [ "$ROUNDS" -gt 3 ]; then
  echo "⛔ 拒绝运行：单次会话 ≤ 3 轮（--rounds=$ROUNDS）。这是 2026-10-02 事故后定的硬上限。" >&2
  exit 4
fi

LABELS=$(echo "$LABELS" | tr '|' '\n' | sed '/^$/d')
EXPTS=$(echo "$EXPTS"  | tr '|' '\n' | sed '/^$/d')
N=$(echo "$LABELS" | wc -l | tr -d ' ')

cd "$ND"
export LD_LIBRARY_PATH="$ND"
export DSP_LIBRARY_PATH="$ND"
export ADSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"

BODY="The Hexagon Tensor Processor runs quantized matrix multiplication inside the mobile SoC, keeping the weights resident in DDR while the vector units stream tiles through VTCM."
# ⚠️ 生成任务必须**强制长输出**：用「数数」这类不会提前 EOS 的任务。
# 教训：之前用 "Reply with the single word OK"，模型回 2 个 token 就 stop，
# `predicted_per_second` 实际是「1~2 个 token 的样本」= 首 token 延迟，噪声极大。
mkreq() {
  P=""; i=0
  while [ $i -lt 10 ]; do P="$P $BODY"; i=$((i+1)); done
  printf '{"messages":[{"role":"user","content":"%s Ignore the text above except for its length. Now count from 1 to 300, one number per line, with nothing else in your reply."}],"max_tokens":400,"temperature":0,"cache_prompt":false}' "$P" > /data/local/tmp/ab_req.json
}

run_one() { # $1=label $2=extra $3=round $4=port
  LAB="$1"; EX="$2"; RD="$3"; PORT="$4"
  LOG="/data/local/tmp/ab_${RD}_${PORT}.log"
  # shellcheck disable=SC2086
  setsid nohup ./libllama-server.so -m "$MODEL" --device "$DEV" -ngl 99 -c "$CTX" -fa on \
    --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 $EX --host 127.0.0.1 --port "$PORT" >"$LOG" 2>&1 &
  PID=$!
  j=0; OK=0
  while [ $j -lt 40 ]; do
    sleep 3; j=$((j+1))
    c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/health")
    [ "$c" = "200" ] && { OK=1; break; }
    kill -0 $PID 2>/dev/null || break
  done
  if [ "$OK" != "1" ]; then
    echo "RESULT|$LAB|DEAD|0|0"
    kill $PID 2>/dev/null; return
  fi
  mkreq "warm-$RD-$PORT"; curl -s --max-time 200 -o /dev/null "http://127.0.0.1:$PORT/v1/chat/completions" -H 'Content-Type: application/json' -d @/data/local/tmp/ab_req.json
  mkreq "arm-$RD-$PORT"
  R=$(curl -s --max-time 250 "http://127.0.0.1:$PORT/v1/chat/completions" -H 'Content-Type: application/json' -d @/data/local/tmp/ab_req.json)
  PP=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_per_second":[0-9.]*' | cut -d: -f2)
  PD=$(echo "$R" | tr ',' '\n' | grep -o '"predicted_per_second":[0-9.]*' | cut -d: -f2)
  CN=$(echo "$R" | tr ',' '\n' | grep -o '"cache_n":[0-9]*' | cut -d: -f2)
  PN=$(echo "$R" | tr ',' '\n' | grep -o '"predicted_n":[0-9]*' | cut -d: -f2)
  if [ "${CN:-1}" != "0" ]; then echo "!! 警告：cache_n=$CN（KV 被复用了，数据不可信）" >&2; fi
  if [ "${PN:-0}" -lt 32 ]; then echo "!! 警告：只生成了 ${PN:-0} 个 token（decode 样本太小，等于在测首 token 延迟）" >&2; fi
  echo "RESULT|$LAB|OK|${PP:-0}|${PD:-0}"
  kill $PID 2>/dev/null
  # 等内存真正回收再起下一轮（2026-10-02 事故：原来只 sleep 4s，
  # 上一轮的 page cache / DMA 缓冲没回收，反复 2.5GB 加载把 MemAvailable 反复打到底）。
  i=0
  while [ $i -lt 30 ]; do
    sleep 2; i=$((i+1))
    a=$(avail_mb)
    [ "${a:-0}" -ge "$NEED_MB" ] && break
  done
  t=$(batt_temp)
  if overheat; then
    echo "⛔ 已过热（电池 $(batt_temp)°C / CPU $(cpu_rep_c)°C），中止后续轮次。" >&2
    exit 5
  fi
}

median() { sort -n | awk '{a[NR]=$1} END{ if(NR==0){print "-"; exit} print (NR%2? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2) }'; }

TMP=$(mktemp)
PORTBASE=19000
round=1
while [ $round -le "$ROUNDS" ]; do
  i=1
  while [ $i -le "$N" ]; do
    lab=$(echo "$LABELS" | sed -n "${i}p")
    ex=$(echo "$EXPTS" | sed -n "${i}p")
    port=$((PORTBASE + i))
    out=$(run_one "$lab" "$ex" "$round" "$port")
    echo "$out" | grep -v '^RESULT|' || true
    echo "$out" | grep '^RESULT|' >> "$TMP"
    i=$((i+1))
  done
  round=$((round+1))
done

echo
echo "===== 中位数汇总（${ROUNDS} 轮，$(date '+%F %T')）====="
printf '%-22s %12s %14s %s\n' "变体" "prefill t/s" "decode t/s" "样本"

i=1
while [ $i -le "$N" ]; do
  lab=$(echo "$LABELS" | sed -n "${i}p")
  pf=$(awk -F'|' -v L="$lab" '$2==L && $3=="OK"{print $4}' "$TMP" | median)
  pd=$(awk -F'|' -v L="$lab" '$2==L && $3=="OK"{print $5}' "$TMP" | median)
  cnt=$(awk -F'|' -v L="$lab" '$2==L && $3=="OK"' "$TMP" | wc -l | tr -d ' ')
  printf '%-22s %12s %14s %s\n' "$lab" "$pf" "$pd" "$cnt"
  i=$((i+1))
done
rm -f "$TMP"

# ── 收尾健康检查（2026-10-02 事故后新增）────────────────────────────────────
# 那次是「基准跑完 → 手机失联」，所以跑完必须自证没有留下烂摊子。
echo
echo "===== 收尾健康检查 ====="
LEFT=$(ps -A -o args 2>/dev/null | grep -c 'libllama[-]server' || true)
echo "  残留 llama-server 进程：$LEFT（应为 0）"
echo "  MemAvailable：$(avail_mb)MB   电池：$(batt_temp)°C"
echo "  ⚠️ 请自行确认移动网络/电话栈正常；若出现无服务，冷却→飞行模式→重启。"
echo "  ⚠️ 别忘了在沙箱里执行：gradle --stop"
