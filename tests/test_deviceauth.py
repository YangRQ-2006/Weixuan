import json
import os
import stat

import pytest

from mobilerun.cli import deviceauth


def _mode(path):
    return stat.S_IMODE(path.stat().st_mode)


def test_save_token_writes_private_file_and_repairs_directory_permissions(
    monkeypatch, tmp_path
):
    credential_dir = tmp_path / "credentials"
    credential_dir.mkdir(mode=0o755)
    credential_file = credential_dir / "mobilerun-cloud.json"
    monkeypatch.setattr(deviceauth, "credential_path", lambda: credential_file)

    path = deviceauth.save_token("secret-token", "https://cloud.mobilerun.ai/api/auth")

    assert path == credential_file
    assert json.loads(path.read_text()) == {
        "access_token": "secret-token",
        "auth_url": "https://cloud.mobilerun.ai/api/auth",
    }
    if os.name != "nt":
        assert _mode(credential_dir) == 0o700
        assert _mode(path) == 0o600


def test_save_token_overwrites_existing_token_privately(monkeypatch, tmp_path):
    credential_dir = tmp_path / "credentials"
    credential_dir.mkdir()
    credential_file = credential_dir / "mobilerun-cloud.json"
    credential_file.write_text("old")
    if os.name != "nt":
        os.chmod(credential_file, 0o644)
    monkeypatch.setattr(deviceauth, "credential_path", lambda: credential_file)

    deviceauth.save_token("new-token", "https://auth.example")

    assert json.loads(credential_file.read_text()) == {
        "access_token": "new-token",
        "auth_url": "https://auth.example",
    }
    if os.name != "nt":
        assert _mode(credential_file) == 0o600


def test_clear_credentials_removes_file_and_is_idempotent(monkeypatch, tmp_path):
    credential_file = tmp_path / "credentials" / "mobilerun-cloud.json"
    credential_file.parent.mkdir()
    credential_file.write_text("{}")
    monkeypatch.setattr(deviceauth, "credential_path", lambda: credential_file)

    assert deviceauth.clear_credentials() is True
    assert credential_file.exists() is False
    assert deviceauth.clear_credentials() is False


def test_poll_token_handles_pending_then_success(monkeypatch):
    code = deviceauth.CodeResponse(
        device_code="device-code",
        user_code="USER",
        verification_uri="https://example.test",
        verification_uri_complete="https://example.test/complete",
        expires_in=60,
        interval=1,
    )
    calls = []

    def fake_exchange(_auth_url, _client_id, _device_code):
        calls.append("exchange")
        if len(calls) == 1:
            raise deviceauth.DeviceAuthError("authorization_pending")
        return "token"

    monkeypatch.setattr(deviceauth, "_exchange", fake_exchange)
    monkeypatch.setattr(deviceauth.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(deviceauth.time, "monotonic", lambda: len(calls))

    assert deviceauth.poll_token("https://auth.example", "client", code) == "token"
    assert calls == ["exchange", "exchange"]


def test_poll_token_honors_slow_down(monkeypatch):
    code = deviceauth.CodeResponse(
        device_code="device-code",
        user_code="USER",
        verification_uri="https://example.test",
        verification_uri_complete="https://example.test/complete",
        expires_in=60,
        interval=1,
    )
    calls = []
    sleeps = []

    def fake_exchange(_auth_url, _client_id, _device_code):
        calls.append("exchange")
        if len(calls) == 1:
            raise deviceauth.DeviceAuthError("slow_down")
        return "token"

    monkeypatch.setattr(deviceauth, "_exchange", fake_exchange)
    monkeypatch.setattr(deviceauth.time, "sleep", sleeps.append)
    monkeypatch.setattr(deviceauth.time, "monotonic", lambda: len(calls))

    assert deviceauth.poll_token("https://auth.example", "client", code) == "token"
    assert sleeps == [5.0, 10.0]


def test_poll_token_expires_pending_authorization(monkeypatch):
    code = deviceauth.CodeResponse(
        device_code="device-code",
        user_code="USER",
        verification_uri="https://example.test",
        verification_uri_complete="https://example.test/complete",
        expires_in=0,
        interval=1,
    )
    ticks = iter([0, 1])

    monkeypatch.setattr(
        deviceauth,
        "_exchange",
        lambda *_args: (_ for _ in ()).throw(
            deviceauth.DeviceAuthError("authorization_pending")
        ),
    )
    monkeypatch.setattr(deviceauth.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(deviceauth.time, "monotonic", lambda: next(ticks))

    with pytest.raises(deviceauth.DeviceAuthError, match="expired_token"):
        deviceauth.poll_token("https://auth.example", "client", code)


def test_poll_token_raises_terminal_denial(monkeypatch):
    code = deviceauth.CodeResponse(
        device_code="device-code",
        user_code="USER",
        verification_uri="https://example.test",
        verification_uri_complete="https://example.test/complete",
        expires_in=60,
        interval=1,
    )

    monkeypatch.setattr(
        deviceauth,
        "_exchange",
        lambda *_args: (_ for _ in ()).throw(
            deviceauth.DeviceAuthError("access_denied", "denied")
        ),
    )
    monkeypatch.setattr(deviceauth.time, "sleep", lambda _seconds: None)

    with pytest.raises(deviceauth.DeviceAuthError, match="access_denied"):
        deviceauth.poll_token("https://auth.example", "client", code)
