#!/system/bin/sh
# -nkvo 必需性判决（2026-10-04）
#
# 背景：微玄在附加 --mmproj 时**强制 -nkvo**（KV/注意力留 CPU），因为当时判定
#   「多模态模型的 mRoPE 引入独立 ROPE 算子，HTP 白名单没有它 → ggml_abort」。
#   但 -nkvo 把注意力放回 CPU，KV 越长 CPU 越堵 —— 很可能就是
#   「decode 随 prompt 长度线性下降（每 1000 token −1.4 t/s）」的成因。
#
#   ★ 关键反证：我早先的取证跑用 Qwen3VL **不带 mmproj、不带 -nkvo**，12 轮全部正常。
#     如果 4B 的文本通路本来就能在 HTP 上算 KV/ROPE，那 -nkvo 这笔税就是白付的。
#
# 三组（同一 prompt 3162 token、ctx6144、f16、t4）：
#   P1  mmproj + nkvo      ← 现状（基线，已知 ≈6.26）
#   P2  mmproj + 无 nkvo   ← ★ 核心测试：能跑吗？快多少？
#   P3  无 mmproj + 无 nkvo ← 文本通路在 HTP 上算 KV/ROPE 的纯净上限
# 只调启动参数，不改代码。
set -eu

MODEL=/sdcard/Download/Qwen3VL-4B-Instruct-Q4_K_M.gguf
MMPROJ=/sdcard/Download/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf
CTX=6144; GEN=64; REPS=95
ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "no nativeLibraryDir"; exit 1; }

batt() { dumpsys battery 2>/dev/null | awk -F': *' '/temperature/{print int($2/10)}'; }
cpu() {
  local m=0 v t
  for z in /sys/class/thermal/thermal_zone*; do
    [ -r "$z/type" ] || continue
    t=$(cat "$z/type" 2>/dev/null || echo ""); case "$t" in *trip*) continue;; esac
    case "$t" in cpu-*) ;; *) continue;; esac
    v=$(cat "$z/temp" 2>/dev/null || echo 0); v=$((v/1000)); [ "$v" -gt "$m" ] && m=$v
  done; echo "$m"
}
hot() { [ "$(cpu)" -gt 95 ] || [ "$(batt)" -gt 50 ]; }

cd "$ND"
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"

BODY="The Hexagon Tensor Processor runs quantized matrix multiplication inside the mobile SoC, keeping the weights resident in DDR while the vector units stream tiles through VTCM."
P=""; i=0; while [ $i -lt $REPS ]; do P="$P $BODY"; i=$((i+1)); done
printf '{"messages":[{"role":"user","content":"%s Now count from 1 to 400, one number per line, nothing else."}],"max_tokens":%s,"temperature":0,"cache_prompt":false}' "$P" "$GEN" > /data/local/tmp/nk_req.json

OUT=/data/local/tmp/nkvo.csv; : > "$OUT"

phase() { # $1=label $2=port $3=mmproj(y/n) $4=nkvo(y/n) $5=iters
  LABEL="$1"; PORT="$2"; USE_MM="$3"; USE_NK="$4"; ITERS="$5"
  echo
  echo "===== $LABEL（mmproj=$USE_MM, nkvo=$USE_NK）电池=$(batt)°C CPU=$(cpu)°C 可用=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)MB ====="
  EX=""
  [ "$USE_MM" = "y" ] && EX="--mmproj $MMPROJ"
  [ "$USE_NK" = "y" ] && EX="$EX -nkvo"
  # shellcheck disable=SC2086
  setsid nohup ./libllama-server.so -m "$MODEL" $EX --device HTP0 -ngl 99 -c "$CTX" -fa on \
    --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 --host 127.0.0.1 --port "$PORT" \
    >/data/local/tmp/nk_${LABEL}.log 2>&1 &
  SRV=$!
  j=0; ok=0
  while [ $j -lt 50 ]; do
    sleep 3; j=$((j+1))
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:$PORT/health 2>/dev/null || echo 0)" = "200" ] && { ok=1; break; }
    kill -0 $SRV 2>/dev/null || break
  done
  if [ "$ok" != "1" ]; then
    echo "  ⛔ **加载失败**（进程退出或 health 不就绪）—— 这就是 -nkvo 必需性的直接证据"
    grep -aiE 'abort|cannot run|error|unsupported' /data/local/tmp/nk_${LABEL}.log | tail -3 | cut -c1-175
    kill $SRV 2>/dev/null
    echo "$LABEL|LOAD_FAIL|0|0|0" >> "$OUT"
    sleep 6; return
  fi
  echo "  就绪（约 $((j*3))s）"
  k=1
  while [ $k -le "$ITERS" ]; do
    hot && { echo "  ⛔ 触及保护线，中止"; break; }
    R=$(curl -s --max-time 600 "http://127.0.0.1:$PORT/v1/chat/completions" -H 'Content-Type: application/json' -d @/data/local/tmp/nk_req.json 2>/dev/null || echo '')
    PD=$(echo "$R" | tr ',' '\n' | grep -o '"predicted_per_second":[0-9.]*' | cut -d: -f2)
    PP=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_per_second":[0-9.]*' | cut -d: -f2)
    PN=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_n":[0-9]*' | cut -d: -f2)
    echo "    #$k decode=${PD:-0} prefill=${PP:-0} prompt_n=${PN:-0}"
    echo "$LABEL|${PD:-0}|${PP:-0}|${PN:-0}|$k" >> "$OUT"
    k=$((k+1))
  done
  kill $SRV 2>/dev/null
  sleep 8
}

phase "P1_mm_nkvo"     19501 y y 1
phase "P2_mm_NO_nkvo"  19502 y n 3
phase "P3_nomm_NO_nkvo" 19503 n n 2

echo
echo "===== 判决汇总（prompt ≈3162 token）====="
awk -F'|' '{printf "  %-16s decode=%-10s prefill=%-10s prompt_n=%s\n", $1, $2, $3, $4}' "$OUT"
echo
echo "读法："
echo "  P2 能跑 → -nkvo **不是必需的**，可直接去掉（省下 CPU 注意力那笔税）"
echo "  P2 崩   → -nkvo 确实必需，方向转向其它杠杆"
echo "  P3 vs P2 → 视觉塔（mmproj）本身要吃多少速度"
