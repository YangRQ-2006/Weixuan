import base64
import json
import time
from concurrent.futures import ThreadPoolExecutor
from types import MappingProxyType, SimpleNamespace
from unittest.mock import Mock
from uuid import uuid4

import pytest
from opentelemetry import trace
from opentelemetry.attributes import BoundedAttributes
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import (
    ReadableSpan,
    SpanLimits,
    SpanProcessor,
    TracerProvider,
)
from opentelemetry.sdk.trace.export import (
    SimpleSpanProcessor,
    SpanExporter,
    SpanExportResult,
)
from opentelemetry.sdk.trace.export.in_memory_span_exporter import (
    InMemorySpanExporter,
)

from mobilerun.agent.utils import tracing_setup
from mobilerun.config_manager.config_manager import TracingConfig
from mobilerun.telemetry import langfuse_processor
from mobilerun.telemetry.langfuse_processor import (
    LangfuseSpanProcessor,
    _LangfuseSpanProcessor,
    _LangfuseTracerProvider,
)


@pytest.fixture(autouse=True)
def reset_tracing_state(monkeypatch):
    monkeypatch.setattr(tracing_setup, "_tracing_initialized", False)
    monkeypatch.setattr(tracing_setup, "_tracing_provider", None)
    monkeypatch.setattr(tracing_setup, "_langfuse_client", None)
    monkeypatch.setattr(tracing_setup, "_langfuse_preprocessor", None)
    monkeypatch.setattr(tracing_setup, "_langfuse_tracer_provider", None)
    monkeypatch.setattr(tracing_setup, "_langfuse_setup_attempted", False)
    monkeypatch.setattr(tracing_setup, "_session_id", "test-session")
    monkeypatch.setattr(tracing_setup, "_user_id", "anonymous")
    for name in (
        "LANGFUSE_PUBLIC_KEY",
        "LANGFUSE_SECRET_KEY",
        "LANGFUSE_BASE_URL",
        "LANGFUSE_HOST",
    ):
        monkeypatch.delenv(name, raising=False)

    agent_token = langfuse_processor._current_agent.set(None)
    root_token = langfuse_processor._root_span_context.set(None)
    step_token = langfuse_processor._last_step_span_context.set(None)
    yield
    langfuse_processor._current_agent.reset(agent_token)
    langfuse_processor._root_span_context.reset(root_token)
    langfuse_processor._last_step_span_context.reset(step_token)


class _FakeTracerProvider:
    def __init__(self):
        self.processors = []

    def add_span_processor(self, processor):
        self.processors.append(processor)


def _install_fake_setup(
    monkeypatch, *, auth_result=True, auth_error=None, attach_exporter=True
):
    import langfuse
    from openinference.instrumentation import llama_index as instrumentation
    from openinference.instrumentation.llama_index import _handler

    provider = _FakeTracerProvider()
    clients = []

    class FakeExporter:
        pass

    FakeExporter.__module__ = "langfuse._client.span_processor"
    exporter = FakeExporter()
    instrument_calls = []

    class FakeInstrumentor:
        is_instrumented_by_opentelemetry = False

        def instrument(self):
            instrument_calls.append(True)

    class FakeLangfuse:
        def __init__(self, **kwargs):
            time.sleep(0.01)
            self.kwargs = kwargs
            clients.append(self)
            if attach_exporter:
                kwargs["tracer_provider"].add_span_processor(exporter)

        def auth_check(self):
            if auth_error:
                raise auth_error
            return auth_result

    monkeypatch.setattr(trace, "get_tracer_provider", lambda: provider)
    monkeypatch.setattr(instrumentation, "LlamaIndexInstrumentor", FakeInstrumentor)
    monkeypatch.setattr(_handler, "_encoder", lambda obj: obj)
    monkeypatch.setattr(langfuse, "Langfuse", FakeLangfuse)
    return provider, clients, exporter, instrument_calls


def _langfuse_config(**kwargs):
    values = {
        "enabled": True,
        "provider": "langfuse",
        "langfuse_public_key": "pk-test",
        "langfuse_secret_key": "sk-test",
    }
    values.update(kwargs)
    return TracingConfig(**values)


def test_setup_is_concurrent_safe_and_wraps_one_sdk_processor(monkeypatch):
    provider, clients, exporter, instrument_calls = _install_fake_setup(monkeypatch)
    config = _langfuse_config()

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda _: tracing_setup.setup_tracing(config), range(16)))

    assert len(clients) == 1
    assert instrument_calls == [True]
    assert len(provider.processors) == 1
    assert isinstance(provider.processors[0], _LangfuseSpanProcessor)
    assert provider.processors[0].processor is exporter
    assert provider.processors[0].normalizer is tracing_setup._langfuse_preprocessor
    assert clients[0].kwargs["should_export_span"](object()) is True
    assert tracing_setup._tracing_initialized is True
    assert tracing_setup._tracing_provider == "langfuse"


@pytest.mark.parametrize(
    ("auth_result", "auth_error"),
    [(False, None), (True, RuntimeError("sensitive-detail"))],
)
def test_auth_diagnostic_does_not_retry_or_leak_details(
    monkeypatch, caplog, auth_result, auth_error
):
    _provider, clients, _exporter, _calls = _install_fake_setup(
        monkeypatch, auth_result=auth_result, auth_error=auth_error
    )

    tracing_setup.setup_tracing(_langfuse_config())
    tracing_setup.setup_tracing(_langfuse_config())

    assert len(clients) == 1
    assert tracing_setup._tracing_initialized is True
    assert "sensitive-detail" not in caplog.text


@pytest.mark.parametrize("register_before_failure", [False, True])
def test_failed_client_construction_cannot_accumulate_processors(
    monkeypatch, register_before_failure
):
    import langfuse
    from openinference.instrumentation import llama_index as instrumentation
    from openinference.instrumentation.llama_index import _handler

    provider = _FakeTracerProvider()
    construction_count = 0

    class FakeInstrumentor:
        is_instrumented_by_opentelemetry = True

    class FailingLangfuse:
        def __init__(self, **_kwargs):
            nonlocal construction_count
            construction_count += 1
            if register_before_failure:
                _kwargs["tracer_provider"].add_span_processor(SpanProcessor())
            raise ValueError("sensitive-detail")

    monkeypatch.setattr(trace, "get_tracer_provider", lambda: provider)
    monkeypatch.setattr(instrumentation, "LlamaIndexInstrumentor", FakeInstrumentor)
    monkeypatch.setattr(_handler, "_encoder", lambda obj: obj)
    monkeypatch.setattr(langfuse, "Langfuse", FailingLangfuse)

    tracing_setup.setup_tracing(_langfuse_config())
    tracing_setup.setup_tracing(_langfuse_config())

    assert construction_count == 1
    assert len(provider.processors) == int(register_before_failure)
    if register_before_failure:
        assert isinstance(provider.processors[0], _LangfuseSpanProcessor)
    assert tracing_setup._tracing_initialized is False


@pytest.mark.parametrize("wrapped", [False, True])
def test_setup_rejects_preexisting_langfuse_exporter(monkeypatch, caplog, wrapped):
    provider, clients, _exporter, _calls = _install_fake_setup(monkeypatch)

    class ExistingLangfuseExporter:
        pass

    ExistingLangfuseExporter.__module__ = "langfuse._client.span_processor"
    processor = ExistingLangfuseExporter()
    if wrapped:
        processor = _LangfuseSpanProcessor(processor, LangfuseSpanProcessor())
    provider._active_span_processor = SimpleNamespace(_span_processors=(processor,))

    tracing_setup.setup_tracing(_langfuse_config())

    assert clients == []
    assert provider.processors == []
    assert tracing_setup._tracing_initialized is False
    assert "pk-test" not in caplog.text
    assert "sk-test" not in caplog.text


def test_setup_rejects_same_key_client_owned_by_another_provider(monkeypatch, caplog):
    provider, clients, _exporter, _calls = _install_fake_setup(
        monkeypatch, attach_exporter=False
    )

    tracing_setup.setup_tracing(_langfuse_config())

    assert len(clients) == 1
    assert provider.processors == []
    assert tracing_setup._langfuse_client is None
    assert tracing_setup._tracing_initialized is False
    assert "pk-test" not in caplog.text
    assert "sk-test" not in caplog.text


@pytest.mark.parametrize(
    ("explicit", "base_env", "legacy_env", "expected"),
    [
        ("https://explicit", "https://base", "https://legacy", "https://explicit"),
        ("", "https://base", "https://legacy", "https://base"),
        ("", "", "https://legacy", "https://legacy"),
        ("", "", "", tracing_setup.DEFAULT_LANGFUSE_BASE_URL),
    ],
)
def test_langfuse_base_url_precedence(
    monkeypatch, explicit, base_env, legacy_env, expected
):
    if base_env:
        monkeypatch.setenv("LANGFUSE_BASE_URL", base_env)
    if legacy_env:
        monkeypatch.setenv("LANGFUSE_HOST", legacy_env)

    config = _langfuse_config(langfuse_host=explicit)
    assert tracing_setup._resolve_langfuse_base_url(config) == expected


def test_processor_normalizes_agent_and_llm_metadata():
    class LLM:
        model = "test-model"
        temperature = 0.2

        @staticmethod
        def class_name():
            return "TestLLM"

    agent = SimpleNamespace(
        shared_state=SimpleNamespace(
            instruction="Open Settings",
            agent_memory=["memory"],
            message_history=[],
            current_subgoal=None,
            error_flag_plan=False,
        ),
        config=SimpleNamespace(
            agent=SimpleNamespace(
                reasoning=False,
                after_sleep_action=1,
                manager=SimpleNamespace(vision=False),
                executor=SimpleNamespace(vision=False),
                fast_agent=SimpleNamespace(vision=True),
            ),
            device=SimpleNamespace(
                platform="android", serial="emulator", use_tcp=False
            ),
        ),
        output_model=None,
        fast_agent_llm=LLM(),
        app_opener_llm=None,
    )
    provider = TracerProvider()
    exporter = InMemorySpanExporter()
    _LangfuseTracerProvider(provider, LangfuseSpanProcessor(agent)).add_span_processor(
        SimpleSpanProcessor(exporter)
    )

    with provider.get_tracer("test").start_as_current_span("MobileAgent.run"):
        pass

    attrs = exporter.get_finished_spans()[0].attributes
    metadata = json.loads(attrs["langfuse.observation.input"])
    assert metadata["goal"] == "Open Settings"
    assert metadata["vision_enabled"] is True
    assert metadata["llms"][0] == {
        "role": "fast_agent",
        "provider": "TestLLM",
        "vision": True,
        "model": "test-model",
        "temperature": 0.2,
    }
    assert attrs["langfuse.trace.tags"] == ("fast",)
    provider.shutdown()


class _CollectingExporter(SpanExporter):
    def __init__(self):
        self.spans = []

    def export(self, spans):
        self.spans.extend(spans)
        return SpanExportResult.SUCCESS

    def shutdown(self):
        pass


@pytest.mark.parametrize(
    "span_name", ["droidrun.screenshot", "LLM.achat", "LLM.acomplete"]
)
def test_public_v4_client_exports_custom_span_once_and_uploads_native_media(
    monkeypatch,
    span_name,
):
    from langfuse import Langfuse
    from langfuse._task_manager.media_manager import MediaManager

    media_jobs = []

    def record_media(_self, *, data):
        media_jobs.append(data)

    monkeypatch.setattr(MediaManager, "_process_upload_media_job", record_media)
    provider = TracerProvider()
    exporter = _CollectingExporter()
    preprocessor = LangfuseSpanProcessor()
    before, after = InMemorySpanExporter(), InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(before))
    client = Langfuse(
        public_key=f"pk-test-{uuid4()}",
        secret_key="sk-test",
        base_url="http://127.0.0.1:1",
        tracer_provider=_LangfuseTracerProvider(provider, preprocessor),
        span_exporter=exporter,
        should_export_span=lambda _span: True,
    )
    provider.add_span_processor(SimpleSpanProcessor(after))

    try:
        image = base64.b64encode(b"png-bytes").decode()
        if span_name == "droidrun.screenshot":
            original_attrs = {
                "droidrun.screenshot.image_base64": image,
                "droidrun.screenshot.mime_type": "image/png",
            }
            media_field = "langfuse.observation.output"
        else:
            messages = json.dumps(
                {
                    "messages": [
                        {
                            "role": "user",
                            "blocks": [
                                {
                                    "block_type": "image",
                                    "image": image,
                                    "image_mimetype": "image/png",
                                }
                            ],
                        }
                    ]
                }
            )
            original_attrs = (
                {"llm.prompts": (messages,)}
                if span_name.endswith("complete")
                else {"input.value": messages}
            )
            media_field = "langfuse.observation.input"
        with provider.get_tracer("mobilerun.custom").start_as_current_span(
            span_name, attributes=original_attrs
        ) as span:
            started_attrs = dict(span.attributes)
        client.flush()
        assert provider.force_flush() is True

        assert len(exporter.spans) == 1
        attrs = exporter.spans[0].attributes
        assert "droidrun.screenshot.image_base64" not in attrs
        assert "@@@langfuseMedia:type=image/png" in attrs[media_field]
        assert image not in json.dumps(dict(attrs))
        assert len(media_jobs) == 1
        assert media_jobs[0]["content_bytes"] == b"png-bytes"
        assert media_jobs[0]["media_id"] in attrs[media_field]
        assert media_jobs[0]["observation_id"] == format(
            exporter.spans[0].context.span_id, "016x"
        )
        original = before.get_finished_spans()[0]
        assert after.get_finished_spans()[0] is original
        assert original.attributes == started_attrs
        assert media_field not in original.attributes
        assert exporter.spans[0].context == original.context
    finally:
        client.shutdown()
        provider.shutdown()


@pytest.mark.parametrize(
    ("name", "attributes", "expected_input", "expected_output"),
    [
        (
            "LLM.achat",
            {
                "input.value": '{"messages": [{"role": "user", "blocks": [{"block_type": "text", "text": "hi"}]}]}',
                "output.value": "hello",
            },
            {"messages": [{"role": "user", "content": "hi"}]},
            "hello",
        ),
        (
            "LLM.astream_complete",
            {"llm.prompts": ("hello",), "output.value": "world"},
            "hello",
            "world",
        ),
        (
            "MobileAgent.run",
            {"input.value": "internal", "output.value": "done"},
            None,
            "done",
        ),
        ("step_done", {}, None, None),
    ],
)
def test_frozen_span_normalization(name, attributes, expected_input, expected_output):
    original = ReadableSpan(name=name, attributes=MappingProxyType(attributes))
    processor = Mock(spec=SpanProcessor)
    _LangfuseSpanProcessor(processor, LangfuseSpanProcessor()).on_end(original)
    processor.on_end.assert_called_once()
    snapshot = processor.on_end.call_args.args[0]
    assert snapshot is not original
    assert dict(original.attributes) == attributes
    attrs = snapshot.attributes
    actual_input = attrs.get("langfuse.observation.input")
    if isinstance(expected_input, dict):
        actual_input = json.loads(actual_input)
    assert actual_input == expected_input
    assert attrs.get("langfuse.observation.output") == expected_output
    assert "input.value" not in attrs
    assert "output.value" not in attrs
    if name.endswith("_done"):
        assert attrs["langfuse.observation.level"] == "DEBUG"


def test_snapshot_preserves_span_metadata_and_dropped_counts():
    provider = TracerProvider(
        resource=Resource.create({"service.name": "snapshot-test"}),
        span_limits=SpanLimits(max_attributes=2, max_events=1, max_links=1),
    )
    original_exporter, normalized_exporter = (
        InMemorySpanExporter(),
        InMemorySpanExporter(),
    )
    provider.add_span_processor(SimpleSpanProcessor(original_exporter))
    _LangfuseTracerProvider(provider, LangfuseSpanProcessor()).add_span_processor(
        SimpleSpanProcessor(normalized_exporter)
    )
    tracer = provider.get_tracer("test-library", "1.2.3", "https://schema.example")
    try:
        with tracer.start_as_current_span("parent") as parent:
            links = [trace.Link(parent.get_span_context()) for _ in range(2)]
            with tracer.start_as_current_span("child", links=links) as span:
                span.set_attributes(
                    {"dropped": "yes", "kept": "yes", "output.value": "result"}
                )
                span.add_event("dropped-event")
                span.add_event("kept-event", {"event-key": "value"})
                span.set_status(trace.Status(trace.StatusCode.ERROR, "test-status"))
        original = original_exporter.get_finished_spans()[0]
        snapshot = normalized_exporter.get_finished_spans()[0]
        for field in (
            "name",
            "context",
            "parent",
            "resource",
            "kind",
            "start_time",
            "end_time",
            "status",
            "events",
            "links",
            "instrumentation_scope",
            "dropped_attributes",
            "dropped_events",
            "dropped_links",
        ):
            assert getattr(snapshot, field) == getattr(original, field), field
        with pytest.warns(DeprecationWarning, match="instrumentation_scope"):
            assert snapshot.instrumentation_info == original.instrumentation_info
        assert (
            snapshot.dropped_attributes,
            snapshot.dropped_events,
            snapshot.dropped_links,
        ) == (1, 1, 1)
        assert original.attributes == {"kept": "yes", "output.value": "result"}
        assert snapshot.attributes == {
            "kept": "yes",
            "langfuse.observation.output": "result",
        }
    finally:
        provider.shutdown()


def test_snapshot_preserves_extended_attributes():
    attributes = BoundedAttributes(
        attributes={"custom": {"nested": ["value"]}, "output.value": "done"},
        immutable=True,
        extended_attributes=True,
    )
    original = ReadableSpan(name="span", attributes=attributes)
    processor = Mock(spec=SpanProcessor)
    _LangfuseSpanProcessor(processor, LangfuseSpanProcessor()).on_end(original)
    snapshot = processor.on_end.call_args.args[0]
    assert snapshot.attributes["custom"] == original.attributes["custom"]
    assert original.attributes["output.value"] == "done"
    assert snapshot.attributes["langfuse.observation.output"] == "done"


def test_adapter_delegates_tracing_and_lifecycle():
    provider = TracerProvider()
    adapter = _LangfuseTracerProvider(provider, LangfuseSpanProcessor())
    processor = Mock(spec=SpanProcessor)
    processor.force_flush.return_value = False
    adapter.add_span_processor(processor)
    with adapter.get_tracer("test").start_as_current_span("span") as span:
        pass
    processor.on_start.assert_called_once_with(span, None)
    processor._on_ending.assert_called_once_with(span)
    processor.on_end.assert_called_once()
    assert adapter.force_flush(123) is False
    assert processor.force_flush.call_count == 1
    # TracerProvider subtracts elapsed time from its flush deadline.
    assert 0 <= processor.force_flush.call_args.args[0] <= 123
    adapter.shutdown()
    processor.shutdown.assert_called_once()


def test_owned_pipeline_rejects_duplicate_or_foreign_normalizers(monkeypatch):
    provider, _clients, exporter, _calls = _install_fake_setup(monkeypatch)
    tracing_setup.setup_tracing(_langfuse_config())
    assert tracing_setup._provider_has_owned_langfuse_pipeline(provider)
    owned = provider.processors[0]
    provider.processors.append(exporter)
    assert not tracing_setup._provider_has_owned_langfuse_pipeline(provider)
    provider.processors[:] = [_LangfuseSpanProcessor(exporter, LangfuseSpanProcessor())]
    assert not tracing_setup._provider_has_owned_langfuse_pipeline(provider)
    provider.processors[:] = [owned, owned]
    assert not tracing_setup._provider_has_owned_langfuse_pipeline(provider)


def test_native_image_size_and_error_handling(monkeypatch, caplog):
    monkeypatch.setattr(langfuse_processor, "MAX_IMAGE_SIZE_KB", 0)
    encoded = base64.b64encode(b"x").decode()

    assert (
        LangfuseSpanProcessor._prepare_image_for_native_upload(
            {"image": encoded, "image_mimetype": "image/png"}
        )
        is None
    )
    assert (
        LangfuseSpanProcessor._prepare_image_for_native_upload(
            {"image": "not-base64", "image_mimetype": "image/png"}
        )
        is None
    )
    assert (
        LangfuseSpanProcessor._prepare_image_for_native_upload(
            {"url": f"data:image/png;base64,{encoded}"}
        )
        is None
    )
    assert "not-base64" not in caplog.text


@pytest.mark.parametrize("nested", [False, True])
def test_rejected_image_bytes_are_not_restored_to_observation_attributes(
    monkeypatch, nested
):
    monkeypatch.setattr(langfuse_processor, "MAX_IMAGE_SIZE_KB", 0)
    encoded = base64.b64encode(b"oversized-image").decode()
    message = {
        "role": "user",
        "blocks": [
            {
                "block_type": "image",
                "image": encoded,
                "image_mimetype": "image/png",
            }
        ],
    }
    if nested:
        message = {"json": message}
    attrs = {"input.value": json.dumps({"messages": [message]})}

    LangfuseSpanProcessor()._process_field(attrs, "input")

    assert encoded not in attrs["langfuse.observation.input"]
    assert "input.value" not in attrs


def test_rejected_serialized_content_media_is_removed(monkeypatch):
    monkeypatch.setattr(langfuse_processor, "MAX_IMAGE_SIZE_KB", 0)
    encoded = base64.b64encode(b"oversized-image").decode()
    attrs = {
        "input.value": json.dumps(
            {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/png;base64,{encoded}"
                                },
                            }
                        ],
                    }
                ]
            }
        )
    }

    LangfuseSpanProcessor()._process_field(attrs, "input")

    assert encoded not in attrs["langfuse.observation.input"]
    assert "input.value" not in attrs


@pytest.mark.parametrize(
    "value",
    [
        "data: ordinary non-media text",
        {"message": "data: nested non-media text"},
        ["data: list-contained non-media text"],
        {"uri": "data:text/plain,hello"},
    ],
)
def test_non_media_data_prefixed_serialized_content_is_preserved(value, caplog):
    serialized = json.dumps(value)
    attrs = {"input.value": serialized}

    LangfuseSpanProcessor()._process_field(attrs, "input")

    assert attrs["langfuse.observation.input"] == serialized
    assert "input.value" not in attrs
    assert "skipping upload" not in caplog.text


def test_mixed_serialized_content_preserves_text_and_removes_oversized_media(
    monkeypatch,
):
    monkeypatch.setattr(langfuse_processor, "MAX_IMAGE_SIZE_KB", 0)
    encoded = base64.b64encode(b"oversized-image").decode()
    attrs = {
        "input.value": json.dumps(
            {
                "content": [
                    "data: ordinary non-media text",
                    f"data:image/png;base64,{encoded}",
                ]
            }
        )
    }

    LangfuseSpanProcessor()._process_field(attrs, "input")

    observed = json.loads(attrs["langfuse.observation.input"])
    assert observed["content"] == ["data: ordinary non-media text", ""]
    assert encoded not in attrs["langfuse.observation.input"]
    assert "input.value" not in attrs


def test_malformed_base64_serialized_media_is_removed(caplog):
    attrs = {
        "input.value": json.dumps({"url": "data:image/png;base64,not-valid-base64"})
    }

    LangfuseSpanProcessor()._process_field(attrs, "input")

    assert json.loads(attrs["langfuse.observation.input"])["url"] == ""
    assert "not-valid-base64" not in caplog.text
    assert "input.value" not in attrs


def test_apply_session_context_sets_langfuse_trace_attributes():
    from openinference.semconv.trace import SpanAttributes
    from opentelemetry.context import get_value

    tracing_setup._tracing_initialized = True
    tracing_setup._tracing_provider = "langfuse"
    tracing_setup._session_id = "session-123"
    tracing_setup._user_id = "user-123"

    def read_context():
        tracing_setup.apply_session_context()
        return (
            get_value(SpanAttributes.SESSION_ID),
            get_value(SpanAttributes.USER_ID),
        )

    with ThreadPoolExecutor(max_workers=1) as pool:
        assert pool.submit(read_context).result() == ("session-123", "user-123")
