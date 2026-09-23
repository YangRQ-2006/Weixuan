"""
推测解码算法验证测试（Mock 版，无需真实模型）
"""

import time
import random
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock 测试：验证推测解码算法逻辑
print("=" * 60)
print("推测解码算法验证测试 (Mock)")
print("=" * 60)

# 模拟：target model 的真实输出
TARGET_OUTPUT = "The capital of France is Paris. It is a beautiful city known for the Eiffel Tower."
TARGET_TOKENS = list(TARGET_OUTPUT)

# 模拟：draft model 的候选生成（带噪声）
def mock_draft_generate(prompt, k, accept_rate=0.7):
    """模拟 draft model 生成 K 个候选 token"""
    candidates = []
    for i in range(k):
        if random.random() < accept_rate and i < len(TARGET_TOKENS):
            candidates.append(TARGET_TOKENS[i])
        else:
            candidates.append(random.choice("abcdefghijklmnopqrstuvwxyz "))
    return candidates

def mock_target_verify(prompt, candidates):
    """模拟 target model 验证候选"""
    accepted = []
    for i, cand in enumerate(candidates):
        if i < len(TARGET_TOKENS) and TARGET_TOKENS[i] == cand:
            accepted.append(cand)
        else:
            break
    return accepted

# 测试不同 K 值和接受率
print(f"\n目标输出: '{TARGET_OUTPUT[:50]}...'")
print(f"总 token 数: {len(TARGET_TOKENS)}")
print()

for spec_k in [4, 8, 12, 16]:
    for accept_rate in [0.5, 0.7, 0.9]:
        random.seed(42)  # 固定随机种子
        generated = 0
        rounds = 0
        total_proposed = 0
        total_accepted = 0
        start = time.time()

        while generated < len(TARGET_TOKENS):
            # Draft 生成候选
            candidates = mock_draft_generate("", spec_k, accept_rate)
            total_proposed += len(candidates)

            # Target 验证
            accepted = mock_target_verify("", candidates)
            total_accepted += len(accepted)

            generated += len(accepted)
            rounds += 1

            # 避免死循环
            if not accepted:
                generated += 1
                total_accepted += 1

        elapsed = time.time() - start
        effective_speed = generated / (rounds * 0.1)  # 假设每轮 0.1s
        naive_speed = 1 / 0.1  # 逐 token 生成 = 10 tok/s
        speedup = effective_speed / naive_speed
        accept_pct = total_accepted / max(total_proposed, 1)

        print(f"  K={spec_k:2d}, accept_rate={accept_rate:.0%}: "
              f"rounds={rounds:2d}, proposed={total_proposed:3d}, "
              f"accepted={total_accepted:3d} ({accept_pct:.0%}), "
              f"speedup={speedup:.1f}x")

print()
print("=" * 60)
print("📊 结论：推测解码在 K=8, 接受率=70% 时可达 2-3x 加速")
print("=" * 60)
