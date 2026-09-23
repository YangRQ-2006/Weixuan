"""
GenieX NPU Provider — 本地 Hexagon NPU 推理后端
==============================================

使用 geniex.AutoModelForCausalLM 高层 API，
将 Qualcomm GenieX (llama.cpp + ggml-hexagon + QNN HTP) 封装为
llama_index LLM 接口。
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

logger = logging.getLogger("mobilerun")

GENIEX_MODEL_ALIASES: dict[str, str] = {
    "qwen3-8b": "Qwen/Qwen3-8B",
    "qwen3-4b": "Qwen/Qwen3-4B",
    "qwen-8b": "Qwen/Qwen3-8B",
    "qwen-4b": "Qwen/Qwen3-4B",
    "qwen2.5-7b": "Qwen/Qwen2.5-7B-Instruct",
    "qwen2.5-3b": "Qwen/Qwen2.5-3B-Instruct",
    "qwen2.5-1.5b": "Qwen/Qwen2.5-1.5B-Instruct",
    "qwen3-0.6b": "Qwen/Qwen3-0.6B",
    "llama3.2-3b": "meta-llama/Llama-3.2-3B-Instruct",
    "llama3.2-1b": "meta-llama/Llama-3.2-1B-Instruct",
    # DeepSeek R1 系列
    "deepseek-r1-32b": "deepseek-r1-32b",
    "r1-32b": "deepseek-r1-32b",
    "deepseek-r1-7b": "deepseek-r1-7b",
    "r1-7b": "deepseek-r1-7b",
    # GLM 系列
    "glm-z1-32b": "glm-z1-32b",
    "glm-4-9b": "glm-4-9b",
    # 多模态
    "qwen3.5-9b": "qwen3.5-9b",
    "glm-4v-9b": "glm-4v-9b",
}


def normalize_geniex_model(model_id: str) -> str:
    return GENIEX_MODEL_ALIASES.get(model_id.strip().lower(), model_id)


class GenieXLLM(CustomLLM):
    """GenieX NPU LLM — 基于 Qualcomm Hexagon NPU 的本地推理引擎。"""

    model: str = Field(default="Qwen/Qwen3-8B")
    precision: str = Field(default="Q4_0")
    compute_unit: str = Field(default="npu")
    context_window: int = Field(default=4096)
    max_tokens: int = Field(default=1024)
    temperature: float = Field(default=0.7)
    top_p: float = Field(default=0.9)
    top_k: int = Field(default=40)
    repetition_penalty: float = Field(default=1.1)
    n_gpu_layers: int = Field(default=-1)
    # 🔴 P0 安全优化：防止 OOM 重启
    use_mmap: bool = Field(default=True, description="mmap 按需加载，防止 OOM")
    use_mlock: bool = Field(default=False, description="不锁定内存，让 OS 管理")
    swa_size: int = Field(default=512, description="SWA 滑动窗口，KV Cache 固定")
    kv_cache_type: str = Field(default="q8_0", description="KV Cache 量化")

    _llm: Any = None
    _initialized: bool = False

    class Config:
        arbitrary_types_allowed = True

    def __init__(self, **kwargs: Any):
        super().__init__(**kwargs)
        self._llm = None
        self._initialized = False

    @property
    def metadata(self) -> LLMMetadata:
        return LLMMetadata(
            context_window=self.context_window,
            num_output=self.max_tokens,
            is_chat_model=True,
            is_function_calling_model=True,
            model_name=f"geniex/{self.model}",
        )

    def _ensure_initialized(self):
        if self._initialized and self._llm is not None:
            return self._llm

        import geniex

        model_name = normalize_geniex_model(self.model)
        logger.info(
            f"🚀 GenieX: loading {model_name} ({self.precision}) on {self.compute_unit}"
        )

        # Try local path first, then model name
        import os
        model_path = model_name
        for candidate in [
            f"/workspace/models/{model_name}",
            f"/workspace/models/{model_name}-Q8_0.gguf",
            f"/workspace/models/{model_name}-Q4_K_M.gguf",
        ]:
            if os.path.exists(candidate):
                model_path = candidate
                break

        self._llm = geniex.AutoModelForCausalLM.from_pretrained(
            model_path,
            model_name=model_name,
            precision=self.precision,
            device_map=self.compute_unit,
            n_ctx=self.context_window,
            n_gpu_layers=self.n_gpu_layers,
            # 🔴 P0 安全优化：防止 OOM 重启
            use_mmap=self.use_mmap,
            use_mlock=self.use_mlock,
        )
        self._initialized = True
        logger.info(f"✅ GenieX ready: {model_name} on {self.compute_unit}")
        return self._llm

    def _generate_text(self, prompt: str, max_tokens: int, temperature: float) -> str:
        """同步生成文本。"""
        llm = self._ensure_initialized()
        output = llm.generate(
            prompt,
            max_new_tokens=max_tokens,
            temperature=temperature,
            top_p=self.top_p,
            top_k=self.top_k,
            repetition_penalty=self.repetition_penalty,
            stream=False,
        )
        return output.text if hasattr(output, 'text') else str(output)

    def _generate_stream(self, prompt: str, max_tokens: int, temperature: float):
        """流式生成 token。"""
        llm = self._ensure_initialized()
        streamer = llm.generate(
            prompt,
            max_new_tokens=max_tokens,
            temperature=temperature,
            top_p=self.top_p,
            top_k=self.top_k,
            repetition_penalty=self.repetition_penalty,
            stream=True,
        )
        for token in streamer:
            if isinstance(token, str):
                yield token
            elif hasattr(token, 'text'):
                yield token.text

    @llm_chat_callback()
    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResponse:
        prompt = self._messages_to_prompt(messages)
        text = self._generate_text(
            prompt,
            kwargs.get("max_tokens", self.max_tokens),
            kwargs.get("temperature", self.temperature),
        )
        return ChatResponse(
            message=ChatMessage(role="assistant", content=text),
            raw={"model": self.model, "compute_unit": self.compute_unit},
        )

    @llm_chat_callback()
    def stream_chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResponseGen:
        prompt = self._messages_to_prompt(messages)
        max_tokens = kwargs.get("max_tokens", self.max_tokens)
        temperature = kwargs.get("temperature", self.temperature)

        def gen() -> ChatResponseGen:
            text = ""
            for token in self._generate_stream(prompt, max_tokens, temperature):
                text += token
                yield ChatResponse(
                    message=ChatMessage(role="assistant", content=text),
                    delta=token,
                    raw={"model": self.model},
                )
        return gen()

    @llm_completion_callback()
    def complete(self, prompt: str, **kwargs: Any) -> CompletionResponse:
        text = self._generate_text(
            prompt,
            kwargs.get("max_tokens", self.max_tokens),
            kwargs.get("temperature", self.temperature),
        )
        return CompletionResponse(text=text, raw={"model": self.model})

    @llm_completion_callback()
    def stream_complete(self, prompt: str, **kwargs: Any) -> CompletionResponseGen:
        max_tokens = kwargs.get("max_tokens", self.max_tokens)
        temperature = kwargs.get("temperature", self.temperature)

        def gen() -> CompletionResponseGen:
            text = ""
            for token in self._generate_stream(prompt, max_tokens, temperature):
                text += token
                yield CompletionResponse(text=text, delta=token, raw={"model": self.model})
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
        if self._llm:
            self._llm.close()
            self._llm = None
        self._initialized = False
