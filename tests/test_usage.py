from types import SimpleNamespace

from llama_index.core.base.llms.types import ChatMessage, ChatResponse, MessageRole

from mobilerun.agent.usage import (
    TokenCountingHandler,
    create_tracker,
    get_usage_from_response,
    track_usage,
)
from mobilerun.agent.utils.llm_picker import load_llm


def _openai_responses_chat_response() -> ChatResponse:
    return ChatResponse(
        message=ChatMessage(role=MessageRole.ASSISTANT, content="ok"),
        raw=SimpleNamespace(
            usage=SimpleNamespace(
                input_tokens=3,
                output_tokens=2,
                total_tokens=5,
            )
        ),
    )


def _streamed_responses_chat_response(*, raw, additional_kwargs=None) -> ChatResponse:
    return ChatResponse(
        message=ChatMessage(role=MessageRole.ASSISTANT, content="ok"),
        raw=raw,
        additional_kwargs=additional_kwargs or {},
    )


def test_track_usage_supports_mobilerun_openai_responses_wrapper() -> None:
    llm = load_llm("OpenAIResponses", model="gpt-5.5", api_key="stub")

    tracker = track_usage(llm)

    assert isinstance(tracker, TokenCountingHandler)
    assert tracker.provider == "OpenAIResponses"


def test_create_tracker_supports_mobilerun_openai_responses_wrapper() -> None:
    llm = load_llm("OpenAIResponses", model="gpt-5.5", api_key="stub")

    tracker = create_tracker(llm)

    assert isinstance(tracker, TokenCountingHandler)
    assert tracker.provider == "OpenAIResponses"


def test_openai_responses_wrapper_name_extracts_usage_from_response() -> None:
    usage = get_usage_from_response(
        "MobilerunOpenAIResponses", _openai_responses_chat_response()
    )

    assert usage.request_tokens == 3
    assert usage.response_tokens == 2
    assert usage.total_tokens == 5
    assert usage.requests == 1


def test_openai_responses_class_name_extracts_usage_from_response() -> None:
    usage = get_usage_from_response(
        "openai_responses_llm", _openai_responses_chat_response()
    )

    assert usage.request_tokens == 3
    assert usage.response_tokens == 2
    assert usage.total_tokens == 5
    assert usage.requests == 1


def test_openai_responses_extracts_usage_from_completed_stream_object() -> None:
    chat_response = _streamed_responses_chat_response(
        raw=SimpleNamespace(
            type="response.completed",
            response=SimpleNamespace(
                usage=SimpleNamespace(
                    input_tokens=7,
                    output_tokens=5,
                    total_tokens=12,
                )
            ),
        )
    )

    usage = get_usage_from_response("OpenAIResponses", chat_response)

    assert usage.request_tokens == 7
    assert usage.response_tokens == 5
    assert usage.total_tokens == 12
    assert usage.requests == 1


def test_openai_responses_extracts_usage_from_completed_stream_dict() -> None:
    chat_response = _streamed_responses_chat_response(
        raw={
            "type": "response.completed",
            "response": {
                "usage": {
                    "input_tokens": 11,
                    "output_tokens": 4,
                    "total_tokens": 15,
                }
            },
        }
    )

    usage = get_usage_from_response("OpenAIResponses", chat_response)

    assert usage.request_tokens == 11
    assert usage.response_tokens == 4
    assert usage.total_tokens == 15


def test_openai_responses_extracts_completed_stream_additional_usage_fallback() -> None:
    chat_response = _streamed_responses_chat_response(
        raw={"type": "response.completed"},
        additional_kwargs={
            "usage": {
                "input_tokens": 13,
                "output_tokens": 6,
                "total_tokens": 19,
            }
        },
    )

    usage = get_usage_from_response("xai_oauth", chat_response)

    assert usage.request_tokens == 13
    assert usage.response_tokens == 6
    assert usage.total_tokens == 19


def test_openai_responses_extracts_object_usage_without_raw_event() -> None:
    chat_response = _streamed_responses_chat_response(
        raw=None,
        additional_kwargs={
            "usage": SimpleNamespace(
                input_tokens=17,
                output_tokens=8,
                total_tokens=25,
            )
        },
    )

    usage = get_usage_from_response("OpenAIResponses", chat_response)

    assert usage.request_tokens == 17
    assert usage.response_tokens == 8
    assert usage.total_tokens == 25


def test_track_usage_supports_mobilerun_anthropic_wrapper() -> None:
    llm = load_llm("Anthropic", model="claude-opus-4-8", api_key="stub")

    tracker = track_usage(llm)

    assert isinstance(tracker, TokenCountingHandler)
    assert tracker.provider == "Anthropic"
