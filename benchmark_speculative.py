"""
基准测试：标准 NPU 推理 vs 推测解码
"""

import time
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mobilerun.agent.providers.geniex import GenieXLLM
from mobilerun.agent.providers.speculative import SpeculativeGenieXLLM

TEST_PROMPT = "What is the capital of France? Explain briefly."
MAX_TOKENS = 100


def bench_standard():
    """标准 NPU 推理基准"""
    print("=== 标准 NPU 推理 (GenieXLLM) ===")
    llm = GenieXLLM(
        model="Qwen/Qwen2.5-1.5B-Instruct",
        precision="Q4_K_M",
        compute_unit="cpu",  # 测试用 CPU，实际用 npu
    )
    start = time.time()
    tokens = 0
    try:
        for token in llm._speculative_generate(TEST_PROMPT, MAX_TOKENS, 0.7) if hasattr(llm, '_speculative_generate') else []:
            tokens += 1
        # 如果不支持流式，用 complete
        if tokens == 0:
            resp = llm.complete(TEST_PROMPT, max_tokens=MAX_TOKENS)
            tokens = len(resp.text)
    except Exception as e:
        print(f"  ⚠️ 标准推理失败: {e}")
        return 0, 0

    elapsed = time.time() - start
    speed = tokens / elapsed if elapsed > 0 else 0
    print(f"  tokens: {tokens}, time: {elapsed:.1f}s, speed: {speed:.1f} tok/s")
    return tokens, speed


def bench_speculative():
    """推测解码基准"""
    print("=== 推测解码 (SpeculativeGenieXLLM) ===")
    llm = SpeculativeGenieXLLM(
        model="Qwen/Qwen2.5-1.5B-Instruct",
        precision="Q4_K_M",
        compute_unit="cpu",
        draft_model="Qwen/Qwen2.5-1.5B-Instruct",  # 同模型做 draft 简化测试
        draft_precision="Q4_K_M",
        draft_compute_unit="cpu",
        spec_k=8,
    )
    start = time.time()
    tokens = 0
    try:
        for token in llm._speculative_generate(TEST_PROMPT, MAX_TOKENS, 0.7):
            tokens += 1
    except Exception as e:
        print(f"  ⚠️ 推测解码失败: {e}")
        return 0, 0

    elapsed = time.time() - start
    speed = tokens / elapsed if elapsed > 0 else 0
    print(f"  tokens: {tokens}, time: {elapsed:.1f}s, speed: {speed:.1f} tok/s")
    return tokens, speed


if __name__ == "__main__":
    print("=" * 60)
    print("NPU Agent 推理性能基准测试")
    print("=" * 60)
    print(f"Prompt: {TEST_PROMPT}")
    print(f"Max tokens: {MAX_TOKENS}")
    print()

    t1, s1 = bench_standard()
    print()
    t2, s2 = bench_speculative()

    print()
    print("=" * 60)
    if s1 > 0 and s2 > 0:
        speedup = s2 / s1
        print(f"📊 加速比: {speedup:.1f}x ({s1:.1f} → {s2:.1f} tok/s)")
        if speedup >= 1.5:
            print("✅ 推测解码有效！")
        else:
            print("⚠️ 加速比不够理想，需要调优")
    else:
        print("⚠️ 测试不完整，需要在真机上测试")
    print("=" * 60)
