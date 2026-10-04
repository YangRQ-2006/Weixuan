#!/system/bin/sh
# 加载慢的真正因素隔离（2026-10-04，接冷热假设被推翻之后）
#
# 已确立事实：
#   ① 冷读 vs 热读 几乎无差（10.12 / 9.69 / 11.00 秒）→ 页缓存假设**推翻**
#   ② 同一模型 + 同一 server 二进制：
#        独立跑（app 已停，可用 7GB）→ **10.1 秒**
#        app 里（app 在跑，占 2.5GB）→ **19.56 秒**   差 9.5 秒
# 本脚本隔离两个候选因素：
#   A) **存储路径**：/sdcard/Download（FUSE）vs /data/local/tmp（真 ext4、未加密）
#   B) **内存余量**：人为占位压低 MemAvailable，再加载，看是否退化到 ~19s
# 每次加载后立刻释放，只做 3 次加载。
set -eu

ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "no nativeLibraryDir"; exit 1; }
SRC=/sdcard/Download
MODEL_SRC="$SRC/Qwen3VL-4B-Instruct-Q4_K_M.gguf"
MM_SRC=$(ls "$SRC"/mmproj*.gguf 2>/dev/null | head -1)
[ -f "$MODEL_SRC" ] || { echo "no model"; exit 1; }

echo "[磁盘] /data/local/tmp 可用：$(df -m /data/local/tmp 2>/dev/null | awk 'NR==2{print $4" MB"}')"

# 复制到真 ext4（未加密）——只在缺失时复制一次
if [ ! -f /data/local/tmp/Qwen3VL-4B-Instruct-Q4_K_M.gguf ]; then
  echo "[准备] 复制模型到 /data/local/tmp（真 ext4、未加密）…"
  cp "$MODEL_SRC" /data/local/tmp/Qwen3VL-4B-Instruct-Q4_K_M.gguf || { echo "复制失败（空间不足？）"; exit 2; }
  cp "$MM_SRC" /data/local/tmp/ 2>/dev/null || true
  sync
  echo "[准备] 完成"
fi
MODEL_EXT4=/data/local/tmp/Qwen3VL-4B-Instruct-Q4_K_M.gguf
MM_EXT4=$(ls /data/local/tmp/mmproj*.gguf 2>/dev/null | head -1)

cd "$ND"
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"

batt() { dumpsys battery 2>/dev/null | awk -F': *' '/temperature/{print int($2/10)}'; }
# 正确解析 H.SS.mmm.uuu（第 2 段是秒）
secs() { echo "$1" | awk -F. '{printf "%.2f", $2 + $3/1000 + $4/1000000}'; }

load_and_time() { # $1=label $2=model $3=mmproj $4=port
  LOG=/data/local/tmp/lf_$1.log
  avail=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
  echo
  echo "===== $1  （可用 ${avail}MB，电池 $(batt)°C）====="
  setsid nohup ./libllama-server.so -m "$2" --mmproj "$3" --device HTP0 -ngl 99 -c 6144 \
    -fa on --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 --host 127.0.0.1 --port "$4" >"$LOG" 2>&1 &
  SRV=$!
  j=0
  while [ $j -lt 60 ]; do
    sleep 2; j=$((j+1))
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:$4/health 2>/dev/null || echo 0)" = "200" ] && break
    kill -0 $SRV 2>/dev/null || break
  done
  T0=$(grep -a 'load_model: loading model' "$LOG" | head -1 | sed -n 's/^\([0-9.]*\) .*/\1/p')
  T1=$(grep -a 'loaded multimodal model' "$LOG" | head -1 | sed -n 's/^\([0-9.]*\) .*/\1/p')
  if [ -n "$T0" ] && [ -n "$T1" ]; then
    echo "  ★ 加载耗时 = $(secs "$T1") − $(secs "$T0") = $(awk -v a="$(secs "$T1")" -v b="$(secs "$T0")" 'BEGIN{printf "%.2f", a-b}') 秒"
  else
    echo "  ⛔ 取不到时间戳"; tail -2 "$LOG" | cut -c1-150
  fi
  kill $SRV 2>/dev/null
  sleep 8
}

am force-stop cn.yangrq.weixuan 2>/dev/null || true
sleep 3; kill -9 $(pidof libllama-server.so) 2>/dev/null || true; sleep 5

load_and_time "A_FUSE_sdcard"   "$MODEL_SRC" "$MM_SRC"   19911
load_and_time "B_ext4_tmp"      "$MODEL_EXT4" "$MM_EXT4" 19912

# C) 内存余量：占位 2.5GB 后再从 ext4 加载，看是否退化到 ~19s
echo
echo "===== C 内存余量测试 ====="
echo "  占位 2.5GB（模拟 app 常驻模型）…"
LD_PRELOAD= awk 'BEGIN{ s=""; for(i=0;i<2621;i++) s=s"x"; }' >/dev/null 2>&1 || true
# 用 dd 分配匿名内存不可靠，改用 python 持有
python3 -c "
import ctypes,time,sys
buf=ctypes.create_string_buffer(2500*1024*1024)
for i in range(0,len(buf),4096): buf[i]=1
print('占位完成',flush=True)
time.sleep(120)
" &
HOLD=$!
sleep 12
echo "  占位后可用：$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)MB"
load_and_time "C_ext4_lowmem" "$MODEL_EXT4" "$MM_EXT4" 19913
kill $HOLD 2>/dev/null || true

echo
echo "===== 判决 ====="
echo "  A vs B → 存储路径是否有影响（/sdcard FUSE vs /data/local/tmp ext4）"
echo "  B vs C → 内存余量是否有影响（可用 7GB vs 约 4.5GB）"
echo "  若 A/B/C 都≈10s → app 那 19.5s 另有原因（CPU 争用/其自身流程），需在 app 内测"
echo "done"
