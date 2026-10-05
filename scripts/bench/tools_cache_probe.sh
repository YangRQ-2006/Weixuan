#!/system/bin/sh
# 工具集前缀缓存探针（2026-10-04）
#
# 要回答的问题：为什么「带工具」要等 ~20 秒，「不带工具」一问就答？
#
# 假设（来自代码取证）：
#   OpenAiChatCompletionsProvider.compactTools() 会用 toolTokenBudget(messages, config) 裁剪工具集，
#   而该预算 = localPromptBudget(ctx) - messages.toString().length/3  →  **依赖对话长度**。
#   于是每轮（甚至每轮里的每个 agent round）下发给 llama-server 的 tools 数组都可能不同；
#   而 Qwen3 模板把 tools 渲染在 messages[0] 之后（system 文本 → "# Tools" → 工具 JSON），
#   工具块一变，llama-server 的 prompt 前缀缓存从该处起全部失效 → 整个 ~4.8k token 前缀重算。
#   按你实测 HTP prefill 184 t/s 计：4786/184 ≈ 26s —— 与「20 秒」同量级。
#
# 本脚本用同一台机、同一二进制、同一参数，只改 tools 数组，观察 cache_n / prompt_n：
#   ① none      无工具        → prompt_n 应极小
#   ② full #1   全量 57 工具  → 冷启动，cache_n≈0，prompt_n 最大
#   ③ full #2   同一请求再来  → 若缓存生效：cache_n≈prompt_n、prompt 侧耗时骤降
#   ④ pref #1   换成 28 工具  → 若假设成立：cache_n≈0（工具块变化打断前缀）
#   ⑤ pref #2   再来一次      → 应命中
#   ⑥ full #3   切回全量      → 再次 cache_n≈0（槽内缓存被上一个前缀占住）
#
# 用法：
#   sh tools_cache_probe.sh --model <gguf> [--mmproj <mmproj.gguf>] [--port 19712]
#                           [--tools-full <json>] [--tools-sub <json>] [--gen 8]
# 说明：--tools-* 默认取本目录下的 agent_tools_full.json / agent_tools_pref28.json。
# 只跑 6 次、每次 gen 默认 8 token，decode 侧噪声可忽略；重点是 prefill 侧。
set -eu

MODEL=""; MMPROJ=""; PORT=19712; GEN=8
DIR=$(dirname "$0")
FULL="$DIR/agent_tools_full.json"; SUB="$DIR/agent_tools_pref28.json"

while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --mmproj) MMPROJ="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --gen) GEN="$2"; shift 2 ;;
    --tools-full) FULL="$2"; shift 2 ;;
    --tools-sub) SUB="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done
[ -n "$MODEL" ] || { echo "缺少 --model" >&2; exit 1; }
[ -r "$FULL" ] || { echo "读不到 --tools-full：$FULL" >&2; exit 1; }
[ -r "$SUB" ] || { echo "读不到 --tools-sub：$SUB" >&2; exit 1; }

ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "找不到 nativeLibraryDir" >&2; exit 1; }

avail_mb() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
batt_temp() { dumpsys battery 2>/dev/null | awk -F': *' '/temperature/{print int($2/10)}'; }

# ---- 组装 4 份 payload（只差 tools 字段）----
PROMPT="Reply with the single word: ok"
TMP=/data/local/tmp
printf '{"messages":[{"role":"user","content":"%s"}],"max_tokens":%s,"temperature":0,"stream":false,"cache_prompt":true}' "$PROMPT" "$GEN" > "$TMP/tp_none.json"
printf '{"messages":[{"role":"user","content":"%s"}],"max_tokens":%s,"temperature":0,"stream":false,"cache_prompt":true,"tools":' "$PROMPT" "$GEN" > "$TMP/tp_full.json"
cat "$FULL" >> "$TMP/tp_full.json"; printf '}' >> "$TMP/tp_full.json"
printf '{"messages":[{"role":"user","content":"%s"}],"max_tokens":%s,"temperature":0,"stream":false,"cache_prompt":true,"tools":' "$PROMPT" "$GEN" > "$TMP/tp_sub.json"
cat "$SUB" >> "$TMP/tp_sub.json"; printf '}' >> "$TMP/tp_sub.json"

echo "[环境] 模型 $(stat -c %s "$MODEL" | awk '{printf "%d",$1/1048576}')MB / 可用 $(avail_mb)MB / 电池 $(batt_temp)°C / 端口 $PORT"
echo "[工具] full=$(grep -c '"type"' "$FULL" 2>/dev/null || echo '?') 条  sub=$(grep -c '"type"' "$SUB" 2>/dev/null || echo '?') 条"
echo "[payload] $(wc -c < "$TMP/tp_none.json")B / $(wc -c < "$TMP/tp_full.json")B / $(wc -c < "$TMP/tp_sub.json")B"

cd "$ND"
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"

LOG=/data/local/tmp/tools_probe_srv.log
if [ -n "$MMPROJ" ]; then
  setsid nohup ./libllama-server.so -m "$MODEL" --mmproj "$MMPROJ" -nkvo \
    --device HTP0 -ngl 99 -c 6144 -fa on --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 \
    --host 127.0.0.1 --port "$PORT" >"$LOG" 2>&1 &
else
  setsid nohup ./libllama-server.so -m "$MODEL" \
    --device HTP0 -ngl 99 -c 6144 -fa on --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 \
    --host 127.0.0.1 --port "$PORT" >"$LOG" 2>&1 &
fi
SRV=$!
echo "[server] pid=$SRV 日志=$LOG"

# 等就绪（冷加载 16~20s，给 180s）
j=0
while [ $j -lt 60 ]; do
  c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/health" 2>/dev/null || echo 0)
  [ "$c" = "200" ] && break
  sleep 3; j=$((j+1))
done
if [ "$c" != "200" ]; then echo "⛔ server 未就绪（health=$c），看 $LOG"; kill $SRV 2>/dev/null; exit 1; fi
echo "[server] 已就绪（约 $((j*3))s / 可用 $(avail_mb)MB）"
echo

CSV=/data/local/tmp/tools_cache_probe.csv
: > "$CSV"

probe() { # $1=step label  $2=payload file
  if [ "$(batt_temp)" -gt 50 ] 2>/dev/null; then echo "⛔ 电池 >50°C，中止"; return 1; fi
  R=$(curl -s --max-time 300 "http://127.0.0.1:$PORT/v1/chat/completions" \
        -H 'Content-Type: application/json' -d @"$2" 2>/dev/null || echo '')
  PN=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_n":[0-9]*' | cut -d: -f2)
  CN=$(echo "$R" | tr ',' '\n' | grep -o '"cache_n":[0-9]*' | cut -d: -f2)
  PM=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_ms":[0-9.]*' | cut -d: -f2)
  PP=$(echo "$R" | tr ',' '\n' | grep -o '"prompt_per_second":[0-9.]*' | cut -d: -f2)
  DM=$(echo "$R" | tr ',' '\n' | grep -o '"predicted_ms":[0-9.]*' | cut -d: -f2)
  if [ -z "${PN:-}" ]; then
    echo "  $1  ⛔ 无 timings（响应异常，前 200 字：$(echo "$R" | head -c 200)）"
    return 0
  fi
  printf '  %-26s prompt_n=%-6s cache_n=%-6s prefill=%-8s t/s  prompt_ms=%-9s decode_ms=%s\n' \
    "$1" "$PN" "${CN:-0}" "${PP:-?}" "${PM:-?}" "${DM:-?}"
  printf '%s|%s|%s|%s|%s\n' "$1" "$PN" "${CN:-0}" "${PM:-0}" "${PP:-0}" >> "$CSV"
}

probe "① none（无工具）"          "$TMP/tp_none.json"
probe "② full #1（全量，冷）"      "$TMP/tp_full.json"
probe "③ full #2（全量，重发）"    "$TMP/tp_full.json"
probe "④ sub  #1（换子集）"        "$TMP/tp_sub.json"
probe "⑤ sub  #2（子集重发）"      "$TMP/tp_sub.json"
probe "⑥ full #3（切回全量）"      "$TMP/tp_full.json"

kill $SRV 2>/dev/null

echo
echo "===== 汇总 ====="
awk -F'|' '{printf "%-26s prompt_n=%-6s cache_n=%-6s prefill_ms=%-9s prefill_t/s=%s\n",$1,$2,$3,$4,$5}' "$CSV"
rm -f "$CSV" 2>/dev/null || true
echo
echo "结论读法："
echo "  · ① 的 prompt_n 就是「免工具直答」的真实前缀量级；与 ② 之差 = 工具 schema 的净成本"
echo "  · ② vs ③：cache_n 若≈prompt_n 说明前缀缓存本来是好的；若≈0 说明缓存根本没生效（要查 slot/cache_prompt）"
echo "  · ④ vs ③：cache_n 掉回 0 ⇒ **换工具集就会打断整个前缀**，即 compactTools 的预算抖动会每轮逼出全量重算"
echo "  · ⑥：切回全量仍 cache_n≈0 ⇒ 同一槽内前缀来回切换同样付全价"
echo "  · 用 ② 的 prompt_n / 你实测的 prefill t/s 反推：这就是「带工具要等 N 秒」里的 N"
