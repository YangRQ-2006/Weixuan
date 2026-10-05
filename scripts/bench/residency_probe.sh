#!/system/bin/sh
# 权重驻留探针（2026-10-04）—— 判定「20 秒纯等待」是否 = mmap 权重页被内核回收后重新从存储读入
#
# 现场证据（本次会话实测，供脚本使用者对照）：
#   · app 的 llama-server :18787 /health = ok（自称就绪），但进程 Rss≈8MB、Pss_File≈1.9MB
#     → 权重（模型 Q4_K_M + mmproj Q8_0，合计约 3.4GB **clean file-backed 页**）已被整片回收
#   · 同一设备 MemAvailable 在几分钟内从 1.87GB 摆到 6.70GB，zram 已用 7.0GB→5.3GB
#     → 内存压力是间歇性的（WeChat appbrand0 单进程 900MB）
#   · 于是「下一次请求」要把权重从内部存储重读一遍 ≈ 16~20s（你们实测：内部 ext4 19.56s / /sdcard FUSE 49s）
#
# 本脚本用 drop_caches 把「间歇性内存压力」变成确定性事件，做 A/B：
#   A: 默认 mmap          → drop_caches 后权重被丢弃，下次请求必须重读（慢）
#   B: --no-mmap          → 权重在 anon 内存里，drop_caches 打不到它（快）
# 若 B 明显快，则修复方向 = --no-mmap / mlock / 主动预热，而不是去调工具或上下文参数。
#
# 用法：
#   sh residency_probe.sh --model <gguf> [--mmproj <mmproj.gguf>] [--port 19713] [--ctx 4096]
# 注意：需要 root（drop_caches）；会短时占用与 app 相当的模型内存，跑之前先确认可用内存 >4GB。
set -eu

MODEL=""; MMPROJ=""; PORT=19713; CTX=4096
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --mmproj) MMPROJ="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --ctx) CTX="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done
[ -n "$MODEL" ] || { echo "缺少 --model" >&2; exit 1; }
[ "$(id -u)" = "0" ] || echo "⚠️ 非 root：drop_caches 会失败，结论无效"

ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "找不到 nativeLibraryDir" >&2; exit 1; }

avail_mb() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
batt_temp() { dumpsys battery 2>/dev/null | awk -F': *' '/temperature/{print int($2/10)}'; }

BODY=/data/local/tmp/res_body.json
printf '{"messages":[{"role":"user","content":"Reply with the single word: ok"}],"max_tokens":4,"temperature":0,"stream":false,"cache_prompt":false}' > "$BODY"

run_case() { # $1=A/B 标签  $2=额外参数
  LABEL="$1"; EXTRA="$2"
  echo
  echo "══════ 用例 $LABEL（额外参数：${EXTRA:-无}）  可用内存 $(avail_mb)MB / 电池 $(batt_temp)°C ══════"
  LOG=/data/local/tmp/res_$LABEL.log
  cd "$ND"
  export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
  export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
  export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"
  # 先清掉同端口的残留 server（否则本用例的探针会打到上一个用例的进程上）
  for p in $(ps -A -o PID,ARGS 2>/dev/null | grep "libllama-server.so" | grep "port $PORT" | awk '{print $1}'); do
    kill "$p" 2>/dev/null
  done
  sleep 1
  # 注意：不用 setsid/nohup 包裹，$! 必须是真正的 server pid，否则 kill 杀不掉、
  # 下一个用例会连到旧进程上导致结论作废。
  # shellcheck disable=SC2086
  if [ -n "$MMPROJ" ]; then
    ./libllama-server.so -m "$MODEL" --mmproj "$MMPROJ" $EXTRA \
      --device HTP0 -ngl 99 -c "$CTX" -fa on --no-warmup --cache-reuse 256 -np 1 -t 4 \
      -ctk f16 -ctv f16 --host 127.0.0.1 --port "$PORT" >"$LOG" 2>&1 &
  else
    ./libllama-server.so -m "$MODEL" $EXTRA \
      --device HTP0 -ngl 99 -c "$CTX" -fa on --no-warmup --cache-reuse 256 -np 1 -t 4 \
      -ctk f16 -ctv f16 --host 127.0.0.1 --port "$PORT" >"$LOG" 2>&1 &
  fi
  SRV=$!
  T0=$(date +%s)
  j=0
  while [ $j -lt 60 ]; do
    c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/health" 2>/dev/null || echo 0)
    [ "$c" = "200" ] && break
    sleep 2; j=$((j+1))
  done
  [ "$c" = "200" ] || { echo "⛔ server 未就绪，看 $LOG"; kill $SRV 2>/dev/null; return 1; }
  LOAD=$(( $(date +%s) - T0 ))
  echo "  [加载] 首次就绪耗时 ≈ ${LOAD}s"

  pss_file() { awk '/^Pss_File/{print $2}' /proc/$SRV/smaps_rollup 2>/dev/null || echo '?'; }
  rss() { awk '/^Rss/{print $2}' /proc/$SRV/smaps_rollup 2>/dev/null || echo '?'; }

  echo "  [驻留] 加载后 Rss=$(rss)kB Pss_File=$(pss_file)kB"
  req() { # $1=备注
    R=$(curl -s --max-time 300 "http://127.0.0.1:$PORT/v1/chat/completions" \
          -H 'Content-Type: application/json' -d @"$BODY" 2>/dev/null || echo '')
    PM=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_ms":[0-9.]*' | cut -d: -f2)
    PP=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_per_second":[0-9.]*' | cut -d: -f2)
    echo "  [请求] $1  prompt_ms=${PM:-?}  prefill=${PP:-?} t/s   (Rss=$(rss)kB Pss_File=$(pss_file)kB)"
  }

  req "预热（页都在）"
  echo "  [回收] echo 3 > /proc/sys/vm/drop_caches ..."
  echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || echo "  ⛔ drop_caches 失败"
  echo "  [回收] 之后 Rss=$(rss)kB Pss_File=$(pss_file)kB"
  req "drop_caches 之后（=用户感知的『正在加载模型』）"

  kill $SRV 2>/dev/null
  sleep 2
  for p in $(ps -A -o PID,ARGS 2>/dev/null | grep "libllama-server.so" | grep "port $PORT" | awk '{print $1}'); do
    kill "$p" 2>/dev/null
  done
  sleep 2
}

echo "模型=$MODEL   mmproj=${MMPROJ:-无}   ctx=$CTX"
run_case A ""            # 默认 mmap
run_case B "--no-mmap"   # 权重进 anon

echo
echo "===== 判读 ====="
echo "  · A 的第二次 prompt_ms 若是首次的数十倍，且 Pss_File 掉到接近 0 ⇒ 坐实「权重被回收→重读存储」"
echo "  · B 的第二次 prompt_ms 仍接近首次 ⇒ anon 权重不受回收影响，修复方向确定"
echo "  · 若 A/B 第二次都很快 ⇒ 瓶颈不在权重驻留，回到工具 schema prefill（见 tools_cache_probe.sh）"
echo "  · 若 A 第一次就慢到不可用 ⇒ 内存不足（先看可用内存与 zram 占用）"
