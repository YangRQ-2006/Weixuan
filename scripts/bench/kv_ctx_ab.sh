#!/system/bin/sh
# 方案 C 验证：ctx 大小 × KV 类型 的三组对照（2026-10-04）
#
# 背景：q8_0 KV 在本项目测过三次、结论互相矛盾（2.5× / 1.7× / <2%）。
#   前两次协议已确认有缺陷；第三次的前提「跨轮摆动 2.3×」已被 12 轮连续测量推翻（实际 ±6%）。
#   所以必须用干净协议重测。而 C 方案的真价值不是"更快"，是**同样内存换双倍上下文**：
#       A: ctx 6144  + f16 KV = 6144 × 147KB = 903MB
#       C: ctx 12288 + q8_0   = 12288 ×  74KB = 909MB
#   代价有两笔：① q8_0 本身的速度代价（未知）② ctx 变大的速度代价（已知偏负）。
#   本脚本把这两笔分开量。
#
# 每组只加载 1 次，组内连续测 K 次（同 prompt、cache_prompt=false 强制全量 prefill）。
#
# 用法：sh kv_ctx_ab.sh --model <gguf> --mmproj <mmproj.gguf> [--iters 3] [--gen 64]
set -eu

MODEL=""; MMPROJ=""; ITERS=3; GEN=64
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --mmproj) MMPROJ="$2"; shift 2 ;;
    --iters) ITERS="$2"; shift 2 ;;
    --gen) GEN="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done
[ -n "$MODEL" ] || { echo "缺少 --model" >&2; exit 1; }
[ "$ITERS" -le 5 ] || { echo "⛔ --iters 上限 5" >&2; exit 3; }

ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "找不到 nativeLibraryDir" >&2; exit 1; }

MODEL_MB=$(stat -c %s "$MODEL" 2>/dev/null | awk '{printf "%d", $1/1048576}')
MM_MB=$(stat -c %s "$MMPROJ" 2>/dev/null | awk '{printf "%d", $1/1048576}')
avail_mb() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
batt_temp() { dumpsys battery 2>/dev/null | awk -F': *' '/temperature/{print int($2/10)}'; }
cpu_rep_c() {
  local m=0 v t
  for z in /sys/class/thermal/thermal_zone*; do
    [ -r "$z/type" ] || continue
    t=$(cat "$z/type" 2>/dev/null || echo "")
    case "$t" in *trip*) continue ;; esac
    case "$t" in cpu-*) ;; *) continue ;; esac
    v=$(cat "$z/temp" 2>/dev/null || echo 0); v=$((v / 1000))
    [ "$v" -gt "$m" ] && m=$v
  done
  echo "$m"
}
# 仅保留"硬件保护线"（用户已明确要求忽略常规闸门）：CPU>95 或 电池>50 才停
HARD_CPU=95; HARD_BATT=50
too_hot() {
  local b c; b=$(batt_temp); c=$(cpu_rep_c)
  [ "${c:-0}" -gt "$HARD_CPU" ] || [ "${b:-0}" -gt "$HARD_BATT" ]
}

cd "$ND"
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"

echo "[环境] 模型 ${MODEL_MB}MB / mmproj ${MM_MB}MB / 可用 $(avail_mb)MB / 电池 $(batt_temp)°C / CPU $(cpu_rep_c)°C"

# 固定长 prompt（约 3000 token，贴近 Agent 真实量级），靠 cache_prompt=false 保证每组做同样的活
BODY="The Hexagon Tensor Processor runs quantized matrix multiplication inside the mobile SoC, keeping the weights resident in DDR while the vector units stream tiles through VTCM."
P=""; i=0
while [ $i -lt 90 ]; do P="$P $BODY"; i=$((i+1)); done
printf '{"messages":[{"role":"user","content":"%s Now count from 1 to 400, one number per line, nothing else."}],"max_tokens":%s,"temperature":0,"cache_prompt":false}' "$P" "$GEN" > /data/local/tmp/kv_req.json

: > /data/local/tmp/kv_ctx.csv
run_phase() { # $1=label $2=ctx $3=kvtype $4=port
  LABEL="$1"; CTX="$2"; KV="$3"; PORT="$4"
  echo
  echo "===== $LABEL（ctx=$CTX, KV=$KV）可用 $(avail_mb)MB 电池 $(batt_temp)°C CPU $(cpu_rep_c)°C ====="
  setsid nohup ./libllama-server.so -m "$MODEL" \
    --mmproj "$MMPROJ" -nkvo --device HTP0 -ngl 99 -c "$CTX" -fa on \
    --no-warmup -np 1 -t 4 -ctk "$KV" -ctv "$KV" \
    --host 127.0.0.1 --port "$PORT" >/data/local/tmp/kv_${LABEL}.log 2>&1 &
  SRV=$!
  j=0
  while [ $j -lt 50 ]; do
    sleep 3; j=$((j+1))
    c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/health" 2>/dev/null || echo 0)
    [ "$c" = "200" ] && break
    kill -0 $SRV 2>/dev/null || { echo "  ⛔ 进程提前退出，见 /data/local/tmp/kv_${LABEL}.log"; tail -3 /data/local/tmp/kv_${LABEL}.log | cut -c1-160; kill $SRV 2>/dev/null; return; }
  done
  [ "$c" = "200" ] || { echo "  ⛔ 未就绪（health=$c）"; kill $SRV 2>/dev/null; return; }
  echo "  已就绪（加载耗时约 $((j*3))s）"
  k=1
  while [ $k -le "$ITERS" ]; do
    if too_hot; then echo "  ⛔ 触及硬件保护线（电池 $(batt_temp)°C / CPU $(cpu_rep_c)°C），中止"; break; fi
    R=$(curl -s --max-time 600 "http://127.0.0.1:$PORT/v1/chat/completions" \
          -H 'Content-Type: application/json' -d @/data/local/tmp/kv_req.json 2>/dev/null || echo '')
    PD=$(echo "$R" | tr ',' '\n' | grep -o '"predicted_per_second":[0-9.]*' | cut -d: -f2)
    PP=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_per_second":[0-9.]*' | cut -d: -f2)
    PN=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_n":[0-9]*' | cut -d: -f2)
    CN=$(echo "$R" | tr ',' '\n' | grep -o '"cache_n":[0-9]*' | cut -d: -f2)
    echo "  #$k decode=${PD:-0} prefill=${PP:-0} prompt_n=${PN:-0} cache_n=${CN:-0} 电池=$(batt_temp)°C CPU=$(cpu_rep_c)°C"
    echo "$LABEL|$CTX|$KV|${PD:-0}|${PP:-0}|${PN:-0}|${CN:-0}" >> /data/local/tmp/kv_ctx.csv
    k=$((k+1))
  done
  kill $SRV 2>/dev/null
  sleep 8
}

run_phase "A_ctx6144_f16"   6144  f16   19801
run_phase "B_ctx6144_q8_0"  6144  q8_0  19802
run_phase "C_ctx12288_q8_0" 12288 q8_0  19803

echo
echo "===== 三组汇总（同 prompt、同 gen、同协议）====="
awk -F'|' '
  { key=$1; n[key]++; d[key]+=$4; p[key]+=$5 }
  END {
    printf "%-18s %-7s %-6s %-10s %-10s\n","组","ctx","KV","decode均","prefill均"
    for (k in n) printf "%-18s %-7s %-6s %-10.2f %-10.1f\n", k, (k=="A_ctx6144_f16"?"6144":(k=="B_ctx6144_q8_0"?"6144":"12288")), (k=="A_ctx6144_f16"?"f16":"q8_0"), d[k]/n[k], p[k]/n[k]
  }' /data/local/tmp/kv_ctx.csv
echo
echo "结论读法："
echo "  B vs A 的差 = **纯 q8_0 代价**（ctx 相同）"
echo "  C vs B 的差 = **纯 ctx 变大代价**（KV 类型相同）"
echo "  C vs A 的差 = 方案 C 相对现状的总代价"
