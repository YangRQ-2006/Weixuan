#!/system/bin/sh
# 状态摆动取证：查清 HTP decode 在 ≈16 t/s 与 ≈7.2 t/s 之间跳变的成因
#
# 为什么这样测（而不是又做一次 A/B）：
#   2026-10-02 实测中，同一台机、同一参数、同一模型的 decode 会在两档之间摆 2.3×，
#   而当时的 A/B 协议（每轮重启 server + 跨轮比较）会**把这个摆动错误归因给被测参数**
#   —— 这就是「-ub 快 7%」「q8_0 快/慢 1.7×」三次结论互相矛盾的真凶。
#   本脚本改为：**只启一次 server，在同一进程内连续测 K 次**，每次同步采协变量，
#   把「状态」当成被观察对象本身。
#
# 关键判别器（决定后续怎么修）：
#   io_read_bytes 增量 —— 若慢档的每轮存储读取量远高于快档，说明是
#                        **page cache 被挤出、权重每 token 重读闪存**（存储带宽瓶颈）；
#                        若两档读取量都接近 0，那就是**降频/热节流**（时钟瓶颈）。
#   cpu freq / thermal  —— 若慢档伴随大核掉频或某个 thermal zone 顶格，就是热/调度问题。
#
# 用法（在 Android 宿主上跑，不是 PRoot 沙箱）：
#   sh state_forensics.sh --model <gguf> [--iters 10] [--ctx 2048] [--gen 192] [--pin 0-3]
#
# 安全闸门（2026-10-02 失联事故后立的铁律）：
#   跑前 MemAvailable ≥ 模型×1.2+1GB；电池 ≤40°C；每次迭代前复检温度，超限立即中止。
set -eu

MODEL=""; ITERS=10; CTX=2048; GEN=192; PIN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --iters) ITERS="$2"; shift 2 ;;
    --ctx)   CTX="$2"; shift 2 ;;
    --gen)   GEN="$2"; shift 2 ;;
    --pin)   PIN="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done
[ -n "$MODEL" ] || { echo "缺少 --model" >&2; exit 1; }
[ "$ITERS" -le 20 ] || { echo "⛔ --iters 上限 20（防过热）" >&2; exit 3; }

ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "找不到 nativeLibraryDir" >&2; exit 1; }

# ⚠️ 必须用 awk 做算术：Android 的 sh 是 **32 位有符号**整数运算，
# 2497281664 字节（2.4GB）会直接溢出成 -1797685632 → 除以 1048576 得 -1714MB
# → NEED_MB 变负数 → 内存闸门恒成立、等于形同虚设（2026-10-04 实测踩到）。
# awk 用 double，安全。
MODEL_MB=$(stat -c %s "$MODEL" 2>/dev/null | awk '{printf "%d", $1/1048576}')
[ -n "$MODEL_MB" ] || MODEL_MB=0
NEED_MB=$(awk -v m="$MODEL_MB" 'BEGIN{printf "%d", m*1.2+1024}')
avail_mb() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
batt_temp() { dumpsys battery 2>/dev/null | awk -F': *' '/temperature/{print int($2/10)}'; }
# 停手线用**双条件**（2026-10-03 修正）：只看电池会误拦正常操作——
# 日常电池 39~42°C 很常见，且它滞后于 SoC。危险的是「CPU 顶格 + 电池高」的组合。
# 事故当时：CPU0 99.7°C + 电池 51.8°C；正常态：CPU 62~65°C + 电池 39.9~42.2°C。
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
OVERHEAT_LIMIT_C=42      # 电池
CPU_LIMIT_C=85           # 任意 CPU zone
overheat() { # 返回 0 = 过热需停手
  local b c
  b=$(batt_temp); c=$(cpu_rep_c)
  [ "${b:-0}" -gt "$OVERHEAT_LIMIT_C" ] || [ "${c:-0}" -gt "$CPU_LIMIT_C" ]
}

echo "[预检] 模型 ${MODEL_MB}MB 需 ≥${NEED_MB}MB / 当前 $(avail_mb)MB / 电池 $(batt_temp)°C / CPU代表温 $(cpu_rep_c)°C"
[ "$(avail_mb)" -ge "$NEED_MB" ] || { echo "⛔ 内存不足，拒绝运行" >&2; exit 2; }
overheat && { echo "⛔ 过热（电池 $(batt_temp)°C / CPU $(cpu_rep_c)°C），拒绝运行" >&2; exit 3; }

cd "$ND"
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"

BODY="The Hexagon Tensor Processor runs quantized matrix multiplication inside the mobile SoC, keeping the weights resident in DDR while the vector units stream tiles through VTCM."
P=""; i=0
while [ $i -lt 10 ]; do P="$P $BODY"; i=$((i+1)); done
# cache_prompt=false：强制全量 prefill，cache_n=0，保证每次迭代做**同样的活**
printf '{"messages":[{"role":"user","content":"%s Ignore the above except for its length. Now count from 1 to 400, one number per line, nothing else."}],"max_tokens":%s,"temperature":0,"cache_prompt":false}' "$P" "$GEN" > /data/local/tmp/fo_req.json

PORT=19501
RUNNER=""
[ -n "$PIN" ] && RUNNER="taskset -c $PIN"
echo "[启动] server（pin='${PIN:-无}'）"
# shellcheck disable=SC2086
setsid nohup $RUNNER ./libllama-server.so -m "$MODEL" --device HTP0 -ngl 99 -c "$CTX" -fa on \
  --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 --host 127.0.0.1 --port $PORT \
  >/data/local/tmp/fo_server.log 2>&1 &
SRV=$!
j=0
while [ $j -lt 60 ]; do
  sleep 3; j=$((j+1))
  c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/health" 2>/dev/null || echo 0)
  [ "$c" = "200" ] && break
  kill -0 $SRV 2>/dev/null || { echo "进程提前退出" >&2; tail -5 /data/local/tmp/fo_server.log; exit 1; }
done

# ── 协变量采样 ────────────────────────────────────────────────────────────────
# 每行：cur_freq 各 cluster / 最高温度 zone / 温度 / MemAvailable / Cached / 进程读盘字节
sample() {
  local freqs tzmax tzname cur t av cached io
  freqs=""
  for pol in /sys/devices/system/cpu/cpufreq/policy*; do
    [ -r "$pol/scaling_cur_freq" ] || continue
    cur=$(cat "$pol/scaling_cur_freq" 2>/dev/null || echo 0)
    # 带分隔符，否则多 cluster 会粘成一串无法解读（实测 "9981132"）
    freqs="${freqs:+$freqs/}$(awk -v c="$cur" 'BEGIN{printf "%d", c/1000}')"
  done
  tzmax=0; tzname="?"
  for z in /sys/class/thermal/thermal_zone*; do
    [ -r "$z/temp" ] || continue
    tname=$(cat "$z/type" 2>/dev/null || echo "?")
    # 排除 hw-trip / 阈值类 zone：它们报的是**跳闸阈值**而不是温度（实测 cpu-hw-trip-0 恒为 95），
    # 留在最大值里会把真实温度协变量完全遮住（2026-10-04 踩到）。
    case "$tname" in *trip*|*TRIP*) continue ;; esac
    t=$(cat "$z/temp" 2>/dev/null || echo 0)
    [ "$t" -gt "$tzmax" ] 2>/dev/null && { tzmax=$t; tzname=$tname; }
  done
  tzmax=$(expr $tzmax / 1000)
  av=$(avail_mb)
  cached=$(awk '/^Cached:/{print int($2/1024)}' /proc/meminfo)
  io=$(awk '/^read_bytes:/{print $2}' /proc/$SRV/io 2>/dev/null || echo 0)
  echo "$freqs|$tzname|$tzmax|$av|$cached|$io"
}

OUT=/data/local/tmp/fo_result.csv
echo "iter,decode_tps,prompt_tps,prompt_n,cache_n,cpu_freqs,tzmax_name,tzmax_c,memavail_mb,cached_mb,io_read_bytes_delta" > "$OUT"

prev_io=$(sample | awk -F'|' '{print $6}')
echo "[测量] $ITERS 轮（每轮前复检温度）"
k=1
while [ $k -le "$ITERS" ]; do
  if overheat; then
    echo "⛔ 第 $k 轮前已过热（电池 $(batt_temp)°C / CPU $(cpu_rep_c)°C），中止后续测量" >&2
    break
  fi
  R=$(curl -s --max-time 300 "http://127.0.0.1:$PORT/v1/chat/completions" \
        -H 'Content-Type: application/json' -d @/data/local/tmp/fo_req.json 2>/dev/null || echo '')
  PP=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_per_second":[0-9.]*' | cut -d: -f2)
  PD=$(echo "$R" | tr ',' '\n' | grep -o '"predicted_per_second":[0-9.]*' | cut -d: -f2)
  PN=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_n":[0-9]*' | cut -d: -f2)
  CN=$(echo "$R" | tr ',' '\n' | grep -o '"cache_n":[0-9]*' | cut -d: -f2)
  S=$(sample)
  cur_io=$(echo "$S" | awk -F'|' '{print $6}')
  # 同样用 awk 算差值：read_bytes 会超过 2^31，sh 的 32 位算术后会溢出
  dio=$(awk -v a="$cur_io" -v b="$prev_io" 'BEGIN{printf "%d", a-b}')
  prev_io=$cur_io
  printf "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n" \
    "$k" "${PD:-0}" "${PP:-0}" "${PN:-0}" "${CN:-0}" \
    "$(echo "$S" | awk -F'|' '{print $1}')" "$(echo "$S" | awk -F'|' '{print $2}')" \
    "$(echo "$S" | awk -F'|' '{print $3}')" "$(echo "$S" | awk -F'|' '{print $4}')" \
    "$(echo "$S" | awk -F'|' '{print $5}')" "$dio" >> "$OUT"
  printf "  #%-2s decode=%-7s prefill=%-8s cache_n=%s ioΔ=%sMB\n" "$k" "${PD:-0}" "${PP:-0}" "${CN:-?}" "$((dio/1048576))"
  k=$((k+1))
  [ "${CN:-0}" != "0" ] && echo "     ⚠️ cache_n≠0，本轮 prefill 数据不可信"
done

kill $SRV 2>/dev/null
echo
echo "===== 结果（$OUT）====="
cat "$OUT"
echo
echo "===== 自动分档判别 ====="
awk -F, 'NR>1 && $2>0 {
    s+=$2; n++
    if (n==1 || $2<mn) mn=$2
    if (n==1 || $2>mx) mx=$2
    if ($2>12) { f++; fi+=$11; ff+=$7 }
    else       { sl++; si+=$11; sf+=$7 }
  }
  END {
    if (n==0) { print "  无有效样本"; exit }
    printf "  样本 %d，decode 均值 %.2f t/s，范围 %.2f~%.2f\n", n, s/n, mn, mx
    if (f>0) printf "  快档 %d 次：平均 ioΔ %.1f MB/轮，平均最高 zone %.1f°C\n", f, fi/f/1048576, ff/f
    if (sl>0) printf "  慢档 %d 次：平均 ioΔ %.1f MB/轮，平均最高 zone %.1f°C\n", sl, si/sl/1048576, sf/sl
    print ""
    if (sl>0 && f>0) {
      if (si/sl > fi/f*5) print "  ⇒ 判别：**存储读取量差一个量级** → 是 page cache 被挤出、权重重读闪存"
      else                print "  ⇒ 判别：两档读取量接近 → 不是存储问题，看 CPU 频率与 thermal zone（降频/热节流）"
    } else print "  ⇒ 本次没有出现两档，说明状态稳定（可能是环境已冷却）"
  }' "$OUT"
echo
echo "提示：把结果连同当时的 cpu_freqs / tzmax 一起对比，才能定位到底是哪一档在被压。"
echo "⚠️ 收尾：确认 libllama-server 已清零，并在沙箱执行 gradle --stop"
