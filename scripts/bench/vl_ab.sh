#!/system/bin/sh
# VL+nkvo vs 纯文本：**只加载 2 次**的 A/B（2026-10-04）
#
# 为什么不用 ab.sh：
#   2026-10-04 实测发现，**热的源头是「反复加载模型」而不是推理本身**——
#   ab.sh 每个变体每轮都重启 server（3 轮 × 2 变体 = 6 次加载），实测把 CPU 顶到 99°C；
#   而同一天「1 次加载 + 12 轮推理」全程没超过 85°C。
#   所以本脚本的协议是：**每个变体只加载一次，然后在同一进程内连着测 K 次**。
#
# 对照的两边（同一份 GGUF，只差 mmproj 与 -nkvo）：
#   A) text-only ：不加 --mmproj、不加 -nkvo      → ROPE 融在 HVX_Q_PREP，KV 可全量上 HTP
#   B) VL+nkvo   ：加 --mmproj + -nkvo           → 即微玄 App 的正式配置
#      （-nkvo 把 K/Q/V 与 KV cache 留在 CPU，是因为 mRoPE 引入独立 ROPE 算子、HTP 白名单没有它）
#
# 用法：sh vl_ab.sh --model <gguf> --mmproj <mmproj.gguf> [--iters 3] [--ctx 2048] [--gen 64]
set -eu

MODEL=""; MMPROJ=""; ITERS=3; CTX=2048; GEN=64; ORDER="text-first"
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --mmproj) MMPROJ="$2"; shift 2 ;;
    --iters) ITERS="$2"; shift 2 ;;
    --ctx) CTX="$2"; shift 2 ;;
    --gen) GEN="$2"; shift 2 ;;
    --order) ORDER="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done
[ -n "$MODEL" ] || { echo "缺少 --model" >&2; exit 1; }
[ "$ITERS" -le 6 ] || { echo "⛔ --iters 上限 6（防过热）" >&2; exit 3; }

ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "找不到 nativeLibraryDir" >&2; exit 1; }

MODEL_MB=$(stat -c %s "$MODEL" 2>/dev/null | awk '{printf "%d", $1/1048576}')
[ -n "$MODEL_MB" ] || MODEL_MB=0
NEED_MB=$(awk -v m="$MODEL_MB" 'BEGIN{printf "%d", m*1.2+1024}')
avail_mb() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
batt_temp() { dumpsys battery 2>/dev/null | awk -F': *' '/temperature/{print int($2/10)}'; }
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

echo "[预检] 模型 ${MODEL_MB}MB 需 ≥${NEED_MB}MB / 当前 $(avail_mb)MB / 电池 $(batt_temp)°C / CPU $(cpu_rep_c)°C"
[ "$(avail_mb)" -ge "$NEED_MB" ] || { echo "⛔ 内存不足" >&2; exit 2; }
overheat && { echo "⛔ 已过热，先冷却" >&2; exit 3; }

cd "$ND"
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"

# 固定 prompt、不换 nonce：靠 cache_prompt=false 强制全量 prefill，保证每次做**同样的活**
BODY="The Hexagon Tensor Processor runs quantized matrix multiplication inside the mobile SoC, keeping the weights resident in DDR while the vector units stream tiles through VTCM."
P=""; i=0
while [ $i -lt 10 ]; do P="$P $BODY"; i=$((i+1)); done
printf '{"messages":[{"role":"user","content":"%s Ignore the above except for its length. Now count from 1 to 400, one number per line, nothing else."}],"max_tokens":%s,"temperature":0,"cache_prompt":false}' "$P" "$GEN" > /data/local/tmp/vl_req.json

run_phase() { # $1=label $2=extra $3=port
  LABEL="$1"; EX="$2"; PORT="$3"
  echo
  echo "===== 相位 $LABEL（加载 #$4）====="
  # shellcheck disable=SC2086
  setsid nohup ./libllama-server.so -m "$MODEL" --device HTP0 -ngl 99 -c "$CTX" -fa on \
    --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 $EX --host 127.0.0.1 --port "$PORT" \
    >/data/local/tmp/vl_${LABEL}.log 2>&1 &
  SRV=$!
  j=0
  while [ $j -lt 40 ]; do
    sleep 3; j=$((j+1))
    c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/health" 2>/dev/null || echo 0)
    [ "$c" = "200" ] && break
    kill -0 $SRV 2>/dev/null || { echo "  ⛔ 进程提前退出，看 /data/local/tmp/vl_${LABEL}.log"; kill $SRV 2>/dev/null; return; }
  done
  k=1
  while [ $k -le "$ITERS" ]; do
    if overheat; then
      echo "  ⛔ 第 $k 次请求前已过热（电池 $(batt_temp)°C / CPU $(cpu_rep_c)°C），中止本相位"
      break
    fi
    R=$(curl -s --max-time 300 "http://127.0.0.1:$PORT/v1/chat/completions" \
          -H 'Content-Type: application/json' -d @/data/local/tmp/vl_req.json 2>/dev/null || echo '')
    PP=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_per_second":[0-9.]*' | cut -d: -f2)
    PD=$(echo "$R" | tr ',' '\n' | grep -o '"predicted_per_second":[0-9.]*' | cut -d: -f2)
    PN=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_n":[0-9]*' | cut -d: -f2)
    CN=$(echo "$R" | tr ',' '\n' | grep -o '"cache_n":[0-9]*' | cut -d: -f2)
    echo "  #$k decode=${PD:-0} prefill=${PP:-0} prompt_n=${PN:-0} cache_n=${CN:-0} CPU=$(cpu_rep_c)°C"
    echo "$LABEL|${PD:-0}|${PP:-0}|${PN:-0}|${CN:-0}" >> /data/local/tmp/vl_ab.csv
    k=$((k+1))
    sleep 3
  done
  kill $SRV 2>/dev/null
  sleep 6
}

: > /data/local/tmp/vl_ab.csv
# ⚠️ 顺序会混淆结果（2026-10-04 实测）：第二个跑的相位 CPU 高 7~8°C。
# 所以提供了 --order 做反向对照——只有「两个顺序都得到同一结论」才能判定是 -nkvo 的功劳。
if [ "$ORDER" = "vl-first" ]; then
  echo "[顺序] VL+nkvo 先跑，text-only 后跑（用于分离热态混淆）"
  run_phase "VL+nkvo" "--mmproj $MMPROJ -nkvo" 19702 1
  run_phase "text-only" "" 19701 2
else
  echo "[顺序] text-only 先跑，VL+nkvo 后跑"
  run_phase "text-only" "" 19701 1
  run_phase "VL+nkvo" "--mmproj $MMPROJ -nkvo" 19702 2
fi

echo
echo "===== 汇总（同一 GGUF，只差 mmproj 与 -nkvo）====="
awk -F'|' '
  $1=="text-only" && $5=="0" { a[++na]=$2; sa+=$2; pa+=$3 }
  $1=="VL+nkvo"   && $5=="0" { b[++nb]=$2; sb+=$2; pb+=$3 }
  END {
    if (na==0 || nb==0) { print "  样本不足（text="na" vl="nb"）"; exit }
    printf "  text-only : decode 均值 %.2f t/s（%d 次） / prefill 均值 %.1f\n", sa/na, na, pa/na
    printf "  VL+nkvo   : decode 均值 %.2f t/s（%d 次） / prefill 均值 %.1f\n", sb/nb, nb, pb/nb
    printf "  ⇒ nkvo 速度代价：%.1f%%\n", (sa/na - sb/nb) * 100.0 / (sa/na)
  }' /data/local/tmp/vl_ab.csv
echo
echo "提示：两侧都是同轮相邻执行，若某个相位因过热提前中止，样本数会不等——那就别下结论。"
