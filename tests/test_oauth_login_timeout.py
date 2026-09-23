from __future__ import annotations

import math
from types import SimpleNamespace

import httpx
import pytest

from mobilerun.agent.utils.oauth import (
    anthropic_oauth_llm,
    gemini_oauth_code_assist_llm,
    openai_oauth_llm,
)
from mobilerun.agent.utils.oauth.anthropic_oauth_llm import AnthropicOAuthLLM
from mobilerun.agent.utils.oauth.gemini_oauth_code_assist_llm import (
    GeminiOAuthCodeAssistLLM,
)
from mobilerun.agent.utils.oauth.login_timeout import OAuthLoginDeadline
from mobilerun.agent.utils.oauth.openai_oauth_llm import OpenAIOAuth
from mobilerun.config_manager.auth_profile_store import AuthProfileStore


class _FakeClock:
    def __init__(self) -> None:
        self.now = 10.0
        self.sleeps: list[float] = []

    def __call__(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


@pytest.mark.parametrize("timeout", [0, -1, math.nan, math.inf, -math.inf])
def test_oauth_login_deadline_rejects_non_positive_or_non_finite_timeout(
    timeout: float,
) -> None:
    with pytest.raises(ValueError, match="finite positive"):
        OAuthLoginDeadline(timeout)


def test_oauth_login_deadline_caps_requests_and_never_resets() -> None:
    clock = _FakeClock()
    deadline = OAuthLoginDeadline(10, clock=clock, sleeper=clock.sleep)

    assert deadline.remaining(cap=3) == 3
    clock.now += 4
    assert deadline.remaining() == 6

    limited = deadline.limited_to(2)
    limited.sleep(1)
    assert clock.sleeps == [1]
    assert limited.remaining() == 1

    with pytest.raises(TimeoutError, match="OAuth login timed out"):
        limited.sleep(2)
    assert clock.sleeps == [1, 1]
    assert deadline.remaining() == 4


def test_auth_profile_commit_honors_deadline_and_lock_timeout(tmp_path) -> None:
    path = tmp_path / "auth-profiles.json"
    store = AuthProfileStore(path)

    with pytest.raises(TimeoutError, match="expired before commit"):
        store.update_slot(
            "slot",
            {"accessToken": "secret"},
            before_commit=lambda: (_ for _ in ()).throw(
                TimeoutError("expired before commit")
            ),
        )
    assert not path.exists()

    with store.transaction():
        with pytest.raises(TimeoutError):
            store.update_slot(
                "slot",
                {"accessToken": "secret"},
                lock_timeout=0.01,
            )
    assert not path.exists()


@pytest.mark.parametrize("open_browser", [False, True])
def test_openai_device_flow_preserves_deadline_and_browser_preference(
    monkeypatch,
    tmp_path,
    open_browser: bool,
) -> None:
    clock = _FakeClock()
    deadline = OAuthLoginDeadline(20, clock=clock, sleeper=clock.sleep)
    opened: list[str] = []
    requests: list[tuple[str, float]] = []
    responses = iter(
        [
            httpx.Response(503),
            httpx.Response(
                200,
                json={
                    "device_auth_id": "device",
                    "user_code": "ABCD",
                    "verification_uri": "https://auth.openai.test/device",
                    "expires_in": 60,
                    "interval": 1,
                },
            ),
            httpx.Response(403),
            httpx.Response(
                200,
                json={"authorization_code": "code", "code_verifier": "verifier"},
            ),
            httpx.Response(
                200,
                json={
                    "access_token": "access",
                    "refresh_token": "refresh",
                    "expires_in": 3600,
                },
            ),
        ]
    )

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(
            (request.url.path, float(request.extensions["timeout"]["read"]))
        )
        clock.now += 0.5
        return next(responses)

    llm = OpenAIOAuth(
        model="gpt-5.5",
        oauth_credential_path=str(tmp_path / "auth.json"),
    )
    llm._oauth_manager.http_client = httpx.Client(
        transport=httpx.MockTransport(handler)
    )
    monkeypatch.setattr(
        openai_oauth_llm,
        "open_browser_async",
        lambda url, opener: opened.append(url),
    )

    credentials = llm._login_device_code(
        open_browser=open_browser,
        deadline=deadline,
    )

    assert credentials.access_token == "access"
    assert llm._oauth_manager.credential_store.load() == credentials
    assert [path for path, _ in requests] == [
        "/api/accounts/deviceauth/usercode",
        "/api/accounts/deviceauth/usercode",
        "/api/accounts/deviceauth/token",
        "/api/accounts/deviceauth/token",
        "/oauth/token",
    ]
    assert [timeout for _, timeout in requests] == pytest.approx(
        [20.0, 17.5, 17.0, 15.5, 15.0]
    )
    assert clock.sleeps == [2, 1]
    assert opened == (["https://auth.openai.test/device"] if open_browser else [])


@pytest.mark.parametrize(
    ("llm", "exchange_kwargs"),
    (
        (
            AnthropicOAuthLLM(credential_path=None, timeout=30),
            {
                "code": "code",
                "redirect_uri": "https://example.test/callback",
                "code_verifier": "verifier",
                "state": "state",
            },
        ),
        (
            GeminiOAuthCodeAssistLLM(credential_path=None, timeout=30),
            {
                "code": "code",
                "redirect_uri": "https://example.test/callback",
                "code_verifier": "verifier",
            },
        ),
    ),
)
def test_anthropic_and_gemini_exchange_use_one_remaining_budget(
    llm,
    exchange_kwargs,
) -> None:
    clock = _FakeClock()
    deadline = OAuthLoginDeadline(5, clock=clock, sleeper=clock.sleep)
    timeouts: list[float] = []

    def post(*args, **kwargs):  # type: ignore[no-untyped-def]
        timeouts.append(kwargs["timeout"])
        clock.now += 1
        return SimpleNamespace(
            raise_for_status=lambda: None,
            json=lambda: {
                "access_token": "access",
                "refresh_token": "refresh",
                "expires_in": 3600,
            },
        )

    llm._session = SimpleNamespace(post=post)

    assert (
        llm._exchange_authorization_code(
            **exchange_kwargs,
            deadline=deadline,
        )
        == "access"
    )
    assert timeouts == [5]


@pytest.mark.parametrize("open_browser", [False, True])
@pytest.mark.parametrize(
    ("module", "llm_class", "constructor_kwargs"),
    (
        (anthropic_oauth_llm, AnthropicOAuthLLM, {"credential_path": None}),
        (
            gemini_oauth_code_assist_llm,
            GeminiOAuthCodeAssistLLM,
            {"credential_path": None},
        ),
    ),
)
def test_anthropic_and_gemini_bind_fallback_preserves_deadline_and_browser(
    monkeypatch,
    module,
    llm_class,
    constructor_kwargs,
    open_browser: bool,
) -> None:
    deadline = OAuthLoginDeadline(5)
    calls: list[dict[str, object]] = []
    monkeypatch.setattr(module, "_is_headless_environment", lambda: False)
    monkeypatch.setattr(
        module,
        "HTTPServer",
        lambda *args, **kwargs: (_ for _ in ()).throw(OSError("unavailable")),
    )
    monkeypatch.setattr(
        llm_class,
        "login_headless",
        lambda self, **kwargs: (calls.append(kwargs), "access")[1],
    )

    assert (
        llm_class(**constructor_kwargs).login(
            open_browser=open_browser,
            deadline=deadline,
        )
        == "access"
    )

    assert calls[0]["deadline"] is deadline
    assert calls[0]["open_browser"] is open_browser
