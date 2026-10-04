#!/system/bin/sh
# 评测专用 server：与微玄 App 完全同参数（含 --mmproj + -nkvo + -c 6144），只换端口避让 App
ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
[ -x "$ND/libllama-server.so" ] || { echo "no nativeLibraryDir"; exit 1; }
cd "$ND" || exit 1
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"
PORT=${1:-19710}
setsid nohup ./libllama-server.so \
  -m /sdcard/Download/Qwen3VL-4B-Instruct-Q4_K_M.gguf \
  --mmproj /sdcard/Download/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf -nkvo \
  --device HTP0 -ngl 99 -c 6144 -fa on --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 \
  --host 127.0.0.1 --port "$PORT" >/data/local/tmp/eval_srv.log 2>&1 &
echo "started pid=$! port=$PORT"
