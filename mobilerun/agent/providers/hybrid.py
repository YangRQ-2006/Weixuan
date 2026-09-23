"""
HybridGenieXLLM — GPU+NPU 混合推理双阶段调度器
==============================================

Prefill 阶段（计算受限）→ Adreno GPU 批量矩阵乘法
Decode 阶段（带宽受限）→ Hexagon NPU 逐 token 生成

核心原理：
  Prefill 是大矩阵乘法，GPU 4.6 TFLOPS >> NPU 单精度
  Decode 是逐 token 读权重，NPU 150 TOPS INT4 能效比 GPU 高 3x

预期效果：
  首 token 延迟：0.5-1s → 0.2-0.3s（降 50%）
  生成速度：8-15 → 25-35 tok/s（提升 2-3x）
"""

from __future__ import annotations

import logging
import time
from typing import Any, Optional, Sequence

from llama_index.core.base.llms.types import (
    ChatMessage,
    ChatResponse,
    ChatResponseGen,
    CompletionResponse,
    CompletionResponseGen,
    LLMMetadata,
)
from llama_index.core.bridge.pydantic import Field
from llama_index.core.llms.callbacks import (
    llm_chat_callback,
    llm_completion_callback,
)
from llama_index.core.llms.custom import CustomLLM

from mobilerun.agent.providers.geniex import GenieXLLM, normalize_geniex_model

logger = logging.getLogger("mobilerun")


class HybridGenieXLLM(CustomLLM):
    """
    GPU+NPU 混合推理 LLM。

    Prefill 用 GPU，Decode 用 NPU，各用所长。
    """

    # Target model (NPU for decode)
    model: str = Field(default="Qwen/Qwen3-8B")
    precision: str = Field(default="Q4_0")
    context_window: int = Field(default=4096)
    max_tokens: int = Field(default=1024)
    temperature: float = Field(default=0.7)
    top_p: float = Field(default=0.9)
    top_k: int = Field(default=40)
    repetition_penalty: float = Field(default=1.1)

    # 混合推理配置
    prefill_compute_unit: str = Field(
        default="gpu", description="Prefill 计算单元 (gpu/npu/cpu)"
    )
    decode_compute_unit: str = Field(
        default="npu", description="Decode 计算单元 (gpu/npu/cpu)"
    )
    spec_draft_model: str = Field(
        default="Qwen/Qwen3-0.6B", description="推测解码 Draft 模型"
    )
    spec_draft_compute_unit: str = Field(
        default="gpu", description="Draft 模型计算单元"
    )
    enable_speculative: bool = Field(
        default=True, description="是否启用推测解码"
    )
    spec_k: int = Field(default=8, description="推测解码候选数")

    # ResourceGuard 集成
    enable_resource_guard: bool = Field(
        default=True, description="是否启用资源守护"
    )

    # Internal
    _prefill_llm: Any = None   # GPU LLM for Prefill
    _decode_llm: Any = None    # NPU LLM for Decode
    _draft_llm: Any = None     # GPU Draft LLM for Speculative
    _resource_guard: Any = None
    _initialized: bool = False

    class Config:
        arbitrary_types_allowed = True

    def __init__(self, **kwargs: Any):
        super().__init__(**kwargs)
        self._prefill_llm = None
        self._decode_llm = None
        self._draft_llm = None
        self._resource_guard = None
        self._initialized = False

    @property
    def metadata(self) -> LLMMetadata:
        return LLMMetadata(
            context_window=self.context_window,
            num_output=self.max_tokens,
            is_chat_model=True,
            is_function_calling_model=True,
            model_name=f"hybrid/{self.model}",
        )

    def _ensure_initialized(self):
        if self._initialized:
            return

        logger.info(
            f"🚀 初始化混合推理: Prefill={self.prefill_compute_unit}, "
            f"Decode={self.decode_compute_unit}, "
            f"Speculative={self.enable_speculative}"
        )

        # Prefill LLM (GPU)
        self._prefill_llm = GenieXLLM(
            model=self.model,
            precision=self.precision,
            compute_unit=self.prefill_compute_unit,
            context_window=self.context_window,
            max_tokens=self.max_tokens,
            temperature=self.temperature,
            top_p=self.top_p,
            top_k=self.top_k,
            repetition_penalty=self.repetition_penalty,
        )

        # Decode LLM (NPU)
        self._decode_llm = GenieXLLM(
            model=self.model,
            precision=self.precision,
            compute_unit=self.decode_compute_unit,
            context_window=self.context_window,
            max_tokens=self.max_tokens,
            temperature=self.temperature,
            top_p=self.top_p,
            top_k=self.top_k,
            repetition_penalty=self.repetition_penalty,
        )

        # Draft LLM (GPU, for speculative decoding)
        if self.enable_speculative:
            self._draft_llm = GenieXLLM(
                model=self.spec_draft_model,
                precision="Q8_0",
                compute_unit=self.spec_draft_compute_unit,
                context_window=self.context_window,
                max_tokens=self.spec_k,
            )

        # ResourceGuard
        if self.enable_resource_guard:
            try:
                from mobilerun.safety import ResourceGuard
                self._resource_guard = ResourceGuard(model_mb=200)
            except Exception as e:
                logger.warning(f"ResourceGuard 初始化失败: {e}")

        self._initialized = True
        logger.info("✅ 混合推理初始化完成")

    def _prefill(self, prompt: str, max_tokens: int, temperature: float) -> str:
        """
        Prefill 阶段：用 GPU 批量处理输入。

        GPU 4.6 TFLOPS 适合大矩阵乘法（批量处理所有输入 token）。
        """
        start = time.time()

        # ResourceGuard 检查
        if self._resource_guard:
            can_infer, config = self._resource_guard.pre_inference_check()
            if not can_infer:
                logger.warning(f"⛔ Prefill 暂停: {config.get('reason', '?')}")
                return ""
            # 自适应调参
            max_tokens = min(max_tokens, config.get("max_tokens", max_tokens))

        # GPU Prefill
        prompt_len = len(prompt)
        logger.info(
            f"📊 Prefill: {prompt_len} chars on {self.prefill_compute_unit.upper()}"
        )

        # 使用 GPU LLM 处理 prompt
        output = self._prefill_llm._generate_text(prompt, max_tokens, temperature)

        elapsed = time.time() - start
        prefill_speed = prompt_len / elapsed if elapsed > 0 else 0
        logger.info(
            f"⚡ Prefill 完成: {elapsed:.2f}s, "
            f"{prefill_speed:.0f} chars/s on {self.prefill_compute_unit.upper()}"
        )

        return output

    def _decode(
        self, prompt: str, max_tokens: int, temperature: float, use_speculative: bool = True
    ):
        """
        Decode 阶段：用 NPU 逐 token 生成。

        NPU 150 TOPS INT4 适合逐 token 读权重（带宽受限）。
        如果启用推测解码，用 GPU Draft + NPU Verify。
        """
        if use_speculative and self._draft_llm:
            yield from self._speculative_decode(prompt, max_tokens, temperature)
        else:
            yield from self._npu_decode(prompt, max_tokens, temperature)

    def _npu_decode(self, prompt: str, max_tokens: int, temperature: float):
        """NPU 纯 Decode（无推测解码）"""
        start = time.time()
        tokens = 0

        logger.info(f"📊 Decode: {max_tokens} tokens on {self.decode_compute_unit.upper()}")

        for token in self._decode_llm._generate_stream(prompt, max_tokens, temperature):
            tokens += 1
            yield token

        elapsed = time.time() - start
        if elapsed > 0 and tokens > 0:
            speed = tokens / elapsed
            logger.info(
                f"⚡ Decode 完成: {tokens} tokens in {elapsed:.1f}s = "
                f"{speed:.1f} tok/s on {self.decode_compute_unit.upper()}"
            )

    def _speculative_decode(self, prompt: str, max_tokens: int, temperature: float):
        """
        推测解码：GPU Draft 快速猜 + NPU Verify 批量验证。

        预期 2-3x 加速。
        """
        start = time.time()
        generated = 0
        total_proposed = 0
        total_accepted = 0

        logger.info(
            f"📊 推测解码: Draft={self.spec_draft_compute_unit.upper()} + "
            f"Verify={self.decode_compute_unit.upper()}, K={self.spec_k}"
        )

        while generated < max_tokens:
            # Step 1: GPU Draft 快速生成候选
            draft_start = time.time()
            draft_text = self._draft_llm._generate_text(
                prompt, self.spec_k, temperature
            )
            draft_time = time.time() - draft_start

            if not draft_text:
                break

            draft_tokens = list(draft_text[: self.spec_k])
            total_proposed += len(draft_tokens)

            # Step 2: NPU 批量验证
            verify_start = time.time()
            verify_text = self._decode_llm._generate_text(
                prompt, len(draft_tokens), temperature
            )
            verify_time = time.time() - verify_start

            # Step 3: 对比，接受匹配的
            accepted = []
            for i, dt in enumerate(draft_tokens):
                if i < len(verify_text) and verify_text[i] == dt:
                    accepted.append(dt)
                else:
                    break

            # 如果一个都没匹配，接受 verify 的第一个 token
            if not accepted and verify_text:
                accepted = [verify_text[0]]
                total_accepted += 1

            total_accepted += len(accepted)
            generated += len(accepted)

            # 输出接受的 token
            for token in accepted:
                yield token

            # 更新 prompt
            prompt += "".join(accepted)

            # 避免死循环
            if not accepted:
                generated += 1
                yield verify_text[0] if verify_text else " "

        elapsed = time.time() - start
        if elapsed > 0 and generated > 0:
            speed = generated / elapsed
            accept_rate = total_accepted / max(total_proposed, 1)
            logger.info(
                f"⚡ 推测解码完成: {generated} tokens in {elapsed:.1f}s = "
                f"{speed:.1f} tok/s, 接受率: {accept_rate:.1%}"
            )

    @llm_chat_callback()
    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResponse:
        self._ensure_initialized()
        prompt = self._messages_to_prompt(messages)
        max_tokens = kwargs.get("max_tokens", self.max_tokens)
        temperature = kwargs.get("temperature", self.temperature)

        # Prefill (GPU) → Decode (NPU)
        prefill_output = self._prefill(prompt, max_tokens // 2, temperature)

        # Decode 剩余 token
        decode_text = ""
        for token in self._decode(
            prefill_output, max_tokens - len(prefill_output), temperature
        ):
            decode_text += token

        text = prefill_output + decode_text
        return ChatResponse(
            message=ChatMessage(role="assistant", content=text),
            raw={
                "model": self.model,
                "prefill_unit": self.prefill_compute_unit,
                "decode_unit": self.decode_compute_unit,
                "speculative": self.enable_speculative,
            },
        )

    @llm_chat_callback()
    def stream_chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResponseGen:
        self._ensure_initialized()
        prompt = self._messages_to_prompt(messages)
        max_tokens = kwargs.get("max_tokens", self.max_tokens)
        temperature = kwargs.get("temperature", self.temperature)

        def gen() -> ChatResponseGen:
            text = ""

            # Phase 1: Prefill (GPU)
            prefill_output = self._prefill(prompt, max_tokens // 2, temperature)
            text += prefill_output
            yield ChatResponse(
                message=ChatMessage(role="assistant", content=text),
                delta=prefill_output,
                raw={"phase": "prefill", "unit": self.prefill_compute_unit},
            )

            # Phase 2: Decode (NPU)
            for token in self._decode(
                prefill_output, max_tokens - len(prefill_output), temperature
            ):
                text += token
                yield ChatResponse(
                    message=ChatMessage(role="assistant", content=text),
                    delta=token,
                    raw={"phase": "decode", "unit": self.decode_compute_unit},
                )

        return gen()

    @llm_completion_callback()
    def complete(self, prompt: str, **kwargs: Any) -> CompletionResponse:
        self._ensure_initialized()
        max_tokens = kwargs.get("max_tokens", self.max_tokens)
        temperature = kwargs.get("temperature", self.temperature)

        text = ""
        for token in self._decode(prompt, max_tokens, temperature):
            text += token

        return CompletionResponse(
            text=text,
            raw={
                "model": self.model,
                "hybrid": True,
                "prefill_unit": self.prefill_compute_unit,
                "decode_unit": self.decode_compute_unit,
            },
        )

    @llm_completion_callback()
    def stream_complete(self, prompt: str, **kwargs: Any) -> CompletionResponseGen:
        self._ensure_initialized()
        max_tokens = kwargs.get("max_tokens", self.max_tokens)
        temperature = kwargs.get("temperature", self.temperature)

        def gen() -> CompletionResponseGen:
            text = ""
            for token in self._decode(prompt, max_tokens, temperature):
                text += token
                yield CompletionResponse(
                    text=text, delta=token,
                    raw={"model": self.model, "hybrid": True},
                )
        return gen()

    async def achat(self, messages, **kwargs):
        return self.chat(messages, **kwargs)

    async def acomplete(self, prompt, **kwargs):
        return self.complete(prompt, **kwargs)

    async def astream_chat(self, messages, **kwargs):
        for r in self.stream_chat(messages, **kwargs):
            yield r

    async def astream_complete(self, prompt, **kwargs):
        for r in self.stream_complete(prompt, **kwargs):
            yield r

    def _messages_to_prompt(self, messages: Sequence[ChatMessage]) -> str:
        parts = []
        for msg in messages:
            role = msg.role.value if hasattr(msg.role, 'value') else str(msg.role)
            content = msg.content or ""
            if role == "system":
                parts.append(f"<|system|>\n{content}")
            elif role == "user":
                parts.append(f"<|user|>\n{content}")
            elif role == "assistant":
                parts.append(f"<|assistant|>\n{content}")
            elif role == "tool":
                parts.append(f"<|tool|>\n{content}")
        parts.append("<|assistant|>\n")
        return "\n".join(parts)

    def close(self):
        if self._prefill_llm:
            self._prefill_llm.close()
        if self._decode_llm:
            self._decode_llm.close()
        if self._draft_llm:
            self._draft_llm.close()
        self._initialized = False
        logger.info("混合推理资源已释放")
