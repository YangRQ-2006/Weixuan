#!/bin/bash
# NPU Agent 一键部署脚本
# 用法: bash deploy_npu_agent.sh

set -e
echo "🚀 NPU Agent 部署脚本"
echo "======================"

PROJECT_DIR="/workspace/droidrun-npu"
MODELS_DIR="/workspace/models"

echo ""
echo "📦 1. 检查项目文件..."
for f in \
    "$PROJECT_DIR/mobilerun/agent/providers/geniex.py" \
    "$PROJECT_DIR/mobilerun/agent/providers/speculative.py" \
    "$PROJECT_DIR/mobilerun/safety/guard.py"; do
    [ -f "$f" ] && echo "  ✅ $(basename $f)" || echo "  ❌ $(basename $f) 缺失"
done

echo ""
echo "🧠 2. 检查模型文件..."
for f in \
    "$MODELS_DIR/Qwen3-0.6B-Q8_0.gguf" \
    "$MODELS_DIR/qwen2.5-1.5b-instruct-q4_k_m/qwen2.5-1.5b-instruct-q4_k_m.gguf"; do
    [ -f "$f" ] && echo "  ✅ $(basename $f) ($(du -sh $f | cut -f1))" || echo "  ❌ $(basename $f) 缺失"
done

echo ""
echo "🔧 3. 检查依赖..."
python3 -c "import geniex; print('  ✅ geniex')" 2>/dev/null || echo "  ❌ geniex 未安装"
python3 -c "import llama_index; print('  ✅ llama-index')" 2>/dev/null || echo "  ❌ llama-index 未安装"
python3 -c "from mobilerun.safety import SafetyGuard; print('  ✅ SafetyGuard')" 2>/dev/null || echo "  ❌ SafetyGuard 不可用"

echo ""
echo "⚡ 4. 运行推测解码测试..."
cd "$PROJECT_DIR"
python3 test_speculative_mock.py 2>&1 | tail -5

echo ""
echo "🎯 5. 使用方法:"
echo "  # 标准 NPU 推理"
echo "  mobilerun run '打开设置' --provider geniex"
echo ""
echo "  # 推测解码 (2-3x 加速)"
echo "  mobilerun run '打开设置' --provider speculative"
echo ""
echo "  # 别名"
echo "  mobilerun run '打开设置' --provider npu"
echo "  mobilerun run '打开设置' --provider fast"

echo ""
echo "✅ 部署完成！"
