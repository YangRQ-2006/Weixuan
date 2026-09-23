"""Shared Anthropic model catalog and capability metadata."""

from __future__ import annotations

from collections.abc import MutableMapping
from typing import Any

ANTHROPIC_API_DEFAULT_MODEL = "claude-sonnet-5"
ANTHROPIC_OAUTH_DEFAULT_MODEL = ANTHROPIC_API_DEFAULT_MODEL
ANTHROPIC_FABLE_5_1_MODEL = "claude-fable-5-1"

ANTHROPIC_API_MODELS = (
    ANTHROPIC_API_DEFAULT_MODEL,
    ANTHROPIC_FABLE_5_1_MODEL,
    "claude-opus-5",
    "claude-sonnet-4-6",
    "claude-fable-5",
    "claude-opus-4-8",
    "claude-opus-4-6",
    "claude-haiku-4-5",
)

ANTHROPIC_OAUTH_MODELS = (
    ANTHROPIC_OAUTH_DEFAULT_MODEL,
    ANTHROPIC_FABLE_5_1_MODEL,
    "claude-opus-5",
    "claude-opus-4-7",
    "claude-fable-5",
    "claude-opus-4-8",
    "claude-sonnet-4-6",
    "claude-opus-4-6",
    "claude-haiku-4-5",
)

ANTHROPIC_MODEL_CONTEXT_WINDOWS = {
    "claude-opus-5": 1_000_000,
    "claude-sonnet-5": 1_000_000,
    ANTHROPIC_FABLE_5_1_MODEL: 1_000_000,
    "claude-fable-5": 1_000_000,
    "claude-opus-4-8": 1_000_000,
    "claude-opus-4-7": 1_000_000,
    "claude-opus-4-6": 1_000_000,
    "claude-sonnet-4-6": 1_000_000,
    "claude-haiku-4-5": 200_000,
}

# These models reject sampling controls. Filter them after all provider,
# profile, and per-request kwargs have been merged so an explicit override
# cannot accidentally restore an unsupported field.
ANTHROPIC_MODELS_WITHOUT_SAMPLING_PARAMS = frozenset(
    {
        "claude-opus-5",
        "claude-sonnet-5",
        ANTHROPIC_FABLE_5_1_MODEL,
        "claude-fable-5",
        "claude-opus-4-8",
        "claude-opus-4-7",
    }
)
ANTHROPIC_UNSUPPORTED_SAMPLING_PARAMS = frozenset({"temperature", "top_p", "top_k"})

# Anthropic models with the high-resolution visual-token budget
# (2576 px / 4784 tokens). Unknown ids remain on the conservative standard
# budget rather than assuming capabilities that have not been verified.
ANTHROPIC_HIGHRES_MODELS = frozenset(
    {
        "claude-opus-4-7",
        "claude-opus-4-8",
        "claude-opus-5",
        "claude-sonnet-5",
        ANTHROPIC_FABLE_5_1_MODEL,
        "claude-fable-5",
        "claude-mythos-5",
    }
)


def anthropic_model_context_window(model: object) -> int | None:
    """Return the verified context window for a known Anthropic model."""

    return ANTHROPIC_MODEL_CONTEXT_WINDOWS.get(str(model or "").strip())


def anthropic_model_omits_sampling_params(model: object) -> bool:
    """Whether the model rejects Anthropic sampling controls."""

    return str(model or "").strip() in ANTHROPIC_MODELS_WITHOUT_SAMPLING_PARAMS


def strip_anthropic_sampling_params(
    payload: MutableMapping[str, Any],
) -> MutableMapping[str, Any]:
    """Remove unsupported sampling fields from a final Anthropic payload."""

    model = payload.get("model")
    if anthropic_model_omits_sampling_params(model):
        for param in ANTHROPIC_UNSUPPORTED_SAMPLING_PARAMS:
            payload.pop(param, None)
    return payload
