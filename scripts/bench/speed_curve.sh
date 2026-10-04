#!/system/bin/sh
# 速度主控变量标定（2026-10-04）
#   A) decode = f(prompt_tokens)：ctx6144/f16/t4 下取 4 个长度点
#   B/C) 同 prompt 下 -t 6 / -t 8 —— 唯一没动过的旋钮，且 -nkvo 把注意力放在 CPU，
#        当前只用 4 线程（8 核 SoC），有理由怀疑 CPU 侧没喂饱
# 只调启动参数，不改代码。仅保留硬件保护线（CPU>95 / 电池>50）。
set -eu

MODEL=/sdcard/Download/Qwen3VL-4B-Instruct-Q4_K_M.gguf
MMPROJ=/sdcard/Download/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf
CTX=6144; GEN=64
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

OUT=/data/local/tmp/speed.csv; : > "$OUT"

mkreq() { # $1=reps
  P=""; i=0; while [ $i -lt "$1" ]; do P="$P $BODY"; i=$((i+1)); done
  printf '{"messages":[{"role":"user","content":"%s Now count from 1 to 400, one number per line, nothing else."}],"max_tokens":%s,"temperature":0,"cache_prompt":false}' "$P" "$GEN" > /data/local/tmp/sp_req.json
}

measure() { # $1=label $2=port
  R=$(curl -s --max-time 600 "http://127.0.0.1:$2/v1/chat/completions" -H 'Content-Type: application/json' -d @/data/local/tmp/sp_req.json 2>/dev/null || echo '')
  PD=$(echo "$R" | tr ',' '\n' | grep -o '"predicted_per_second":[0-9.]*' | cut -d: -f2)
  PP=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_per_second":[0-9.]*' | cut -d: -f2)
  PN=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_n":[0-9]*' | cut -d: -f2)
  echo "    $1: decode=${PD:-0} prefill=${PP:-0} prompt_n=${PN:-0} 电池=$(batt)°C CPU=$(cpu)°C"
  echo "$1|${PD:-0}|${PP:-0}|${PN:-0}" >> "$OUT"
}

boot() { # $1=threads $2=port
  setsid nohup ./libllama-server.so -m "$MODEL" --mmproj "$MMPROJ" -nkvo --device HTP0 \
    -ngl 99 -c "$CTX" -fa on --no-warmup -np 1 -t "$1" -ctk f16 -ctv f16 \
    --host 127.0.0.1 --port "$2" >/data/local/tmp/sp_$1.log 2>&1 &
  SRV=$!
  j=0
  while [ $j -lt 50 ]; do
    sleep 3; j=$((j+1))
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:$2/health 2>/dev/null || echo 0)" = "200" ] && { echo "  就绪（约 $((j*3))s）"; return 0; }
    kill -0 $SRV 2>/dev/null || { echo "  ⛔ 进程退出"; tail -2 /data/local/tmp/sp_$1.log | cut -c1-140; return 1; }
  done
  echo "  ⛔ health 未就绪"; return 1
}

echo "[环境] 电池=$(batt)°C CPU=$(cpu)°C 可用=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)MB"

echo
echo "===== A) 长度-速度曲线（-t 4）====="
if boot 4 19901; then
  for reps in 12 35 65 95; do
    hot && { echo "  ⛔ 触及保护线，中止"; break; }
    mkreq "$reps"; measure "A_t4_r${reps}" 19901; sleep 2
  done
  kill $SRV 2>/dev/null
fi
sleep 8

echo
echo "===== B) -t 6（prompt 2150 档）====="
if boot 6 19902; then mkreq 65; measure "B_t6_r65" 19902; sleep 2; measure "B_t6_r65_2" 19902; kill $SRV 2>/dev/null; fi
sleep 8

echo
echo "===== C) -t 8（prompt 2150 档）====="
if boot 8 19903; then mkreq 65; measure "C_t8_r65" 19903; sleep 2; measure "C_t8_r65_2" 19903; kill $SRV 2>/dev/null; fi

echo
echo "===== 汇总 ====="
awk -F'|' '{printf "  %-14s prompt_n=%-5s decode=%-8s prefill=%s\n", $1, $4, $2, $3}' "$OUT"
echo
echo "结论读法："
echo "  A 段看 decode 随 prompt_n 的变化斜率 → 每 1000 token 值多少 t/s"
echo "  B/C 段与 A_t4_r65 比 → 加线程是否有效（同 prompt 同参数，只差 -t）"
