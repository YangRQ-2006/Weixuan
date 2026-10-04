#!/system/bin/sh
# 多模态路径判决：带真实图片的请求，在「KV 上 HTP（无 -nkvo）」下能否正常推理
ND=$(ls -d /data/app/*/cn.yangrq.weixuan*/lib/arm64 2>/dev/null | head -1)
cd "$ND" || exit 1
export LD_LIBRARY_PATH="$ND" ADSP_LIBRARY_PATH="$ND" DSP_LIBRARY_PATH="$ND"
export GGML_BACKEND_PATH="$ND/libggml-cpu-arm64.so"
export WEIXUAN_HEXAGON_BACKEND="$ND/libggml-hexagon-adapter.so"
setsid nohup ./libllama-server.so -m /sdcard/Download/Qwen3VL-4B-Instruct-Q4_K_M.gguf \
  --mmproj /sdcard/Download/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf \
  --device HTP0 -ngl 99 -c 6144 -fa on --no-warmup -np 1 -t 4 -ctk f16 -ctv f16 \
  --host 127.0.0.1 --port 19601 >/data/local/tmp/img_srv.log 2>&1 &
SRV=$!
j=0
while [ $j -lt 40 ]; do
  sleep 3; j=$((j+1))
  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:19601/health 2>/dev/null || echo 0)" = "200" ] && break
  kill -0 $SRV 2>/dev/null || break
done
echo "health=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:19601/health 2>/dev/null || echo 0)（加载约 $((j*3))s）"
echo "--- 带图片请求（无 -nkvo）---"
R=$(curl -s --max-time 600 http://127.0.0.1:19601/v1/chat/completions -H 'Content-Type: application/json' -d @/data/local/tmp/img_req.json 2>/dev/null || echo '')
echo "$R" | head -c 700
echo
echo "--- server 侧有无 abort ---"
grep -aiE 'abort|cannot run|ROPE|unsupported|error' /data/local/tmp/img_srv.log | tail -4 | cut -c1-170
kill $SRV 2>/dev/null
echo "done"
