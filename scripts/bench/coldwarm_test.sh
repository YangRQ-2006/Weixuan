#!/system/bin/sh
# 冷读 / 热读 假设验证（2026-10-04）
#
# 待验假设：app 加载 19.56s、我测试加载 7.86s 的差异源于**页缓存**——
#   app 那次是 app 刚被系统杀过（内存释放 → 页缓存被驱逐）→ 需从存储真读 2.9GB（≈150MB/s）；
#   我那次今天跑了十几次，模型一直在页缓存里 → 只需图构建 + HTP session 初始化。
#
# 判据：用 server 自带时钟取 `loading model` → `loaded multimodal model` 的间隔。
#   COLD 用 `echo 3 > /proc/sys/vm/drop_caches` **主动**制造（不靠碰运气）。
#   假设成立 → COLD≈19s、WARM≈8s、再 COLD≈19s（可复现）
#   假设不成立 → 三次接近 → 说明 app 侧另有拖慢因素，有 ~11s 可白拿
#
# 用**app 自己的模型路径**加载，保证可比性。
set -eu

ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "no nativeLibraryDir"; exit 1; }
MDIR=/sdcard/Download
MODEL="$MDIR/Qwen3VL-4B-Instruct-Q4_K_M.gguf"
MMPROJ=$(ls "$MDIR"/mmproj*.gguf 2>/dev/null | head -1)
[ -f "$MODEL" ] || { echo "找不到模型：$MODEL"; exit 1; }
[ -f "$MMPROJ" ] || { echo "找不到 mmproj"; exit 1; }
echo "[路径] MODEL=$MODEL"
echo "[路径] MMPROJ=$MMPROJ"

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

cd "$ND"
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"

ms() { echo "$1" | awk -F. '{printf "%d", $1*3600000+$2*60000+$3*1000+$4}'; }

phase() { # $1=label $2=port $3=cold(y/n)
  LABEL="$1"; PORT="$2"; COLD="$3"
  echo
  echo "===== $LABEL ====="
  if [ "$COLD" = "y" ]; then
    AVAIL0=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
    sync
    echo "  drop_caches 前可用 ${AVAIL0}MB"
    if echo 3 > /proc/sys/vm/drop_caches 2>/dev/null; then echo "  ✅ 已 drop_caches（制造冷读）"; else echo "  ⚠️ drop_caches 失败（无权限）"; fi
  else
    echo "  不做 drop_caches（热读）"
  fi
  LOG=/data/local/tmp/cw_${LABEL}.log
  setsid nohup ./libllama-server.so -m "$MODEL" --mmproj "$MMPROJ" \
    --device HTP0 -ngl 99 -c 6144 -fa on --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 \
    --host 127.0.0.1 --port "$PORT" >"$LOG" 2>&1 &
  SRV=$!
  j=0; ok=0
  while [ $j -lt 60 ]; do
    sleep 2; j=$((j+1))
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:$PORT/health 2>/dev/null || echo 0)" = "200" ] && { ok=1; break; }
    kill -0 $SRV 2>/dev/null || break
  done
  T0=$(grep -a 'load_model: loading model' "$LOG" | head -1 | sed -n 's/^\([0-9]*\.[0-9]*\.[0-9]*\.[0-9]*\).*/\1/p')
  T1=$(grep -a 'loaded multimodal model' "$LOG" | head -1 | sed -n 's/^\([0-9]*\.[0-9]*\.[0-9]*\.[0-9]*\).*/\1/p')
  if [ -n "$T0" ] && [ -n "$T1" ]; then
    echo "  ★ 加载耗时（server 自带时钟）= $(( $(ms "$T1") - $(ms "$T0") )) ms   [health ok=$ok, 电池=$(batt)°C CPU=$(cpu)°C]"
  else
    echo "  ⛔ 未取到时间戳（ok=$ok）"; grep -aiE 'abort|error' "$LOG" | tail -2 | cut -c1-150
  fi
  kill $SRV 2>/dev/null
  sleep 6
}

# 先确保 app 的引擎不在跑（否则页缓存一定是热的，且内存不够）
am force-stop cn.yangrq.weixuan 2>/dev/null || true
sleep 3
kill -9 $(pidof libllama-server.so) 2>/dev/null || true
sleep 5

phase "1_COLD" 19701 y
phase "2_WARM" 19702 n
phase "3_COLD2" 19703 y

echo
echo "===== 判决 ====="
echo "  ①COLD≈19s 且 ②WARM≈8s 且 ③COLD2≈19s  → 假设成立：差异=页缓存，加载慢是冷读的必然代价"
echo "  三次接近（都≈19s 或 都≈8s）          → 假设不成立：app 侧另有拖慢因素，值得深挖"
echo "  若三次都≈8s                          → app 那次的 19.5s 是当时的环境（内存压力）导致，非结构性"
echo "done"
