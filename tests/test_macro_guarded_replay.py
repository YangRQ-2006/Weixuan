import asyncio
from types import SimpleNamespace

from mobilerun.macro.replay import MacroPlayer
from mobilerun.macro.state import normalize_ui_state


class FakeDriver:
    platform = "android"

    def __init__(self, states):
        self.states = list(states)
        self.taps = []
        self.inputs = []

    async def connect(self):
        return None

    async def get_ui_tree(self):
        if len(self.states) > 1:
            return self.states.pop(0)
        return self.states[0]

    async def tap(self, x, y):
        self.taps.append((x, y))

    async def input_text(self, text, clear=False):
        self.inputs.append((text, clear))
        return True


class FakeCredentialManager:
    def __init__(self, secrets):
        self.secrets = dict(secrets)

    async def resolve_key(self, key):
        if key not in self.secrets:
            raise KeyError(key)
        return self.secrets[key]


def _raw_state(text="Continue", bounds="10,20,110,60"):
    return {
        "a11y_tree": [
            {
                "resourceId": "com.example:id/continue",
                "className": "android.widget.Button",
                "text": text,
                "clickable": True,
                "enabled": True,
                "bounds": bounds,
            }
        ],
        "phone_state": {"package": "com.example", "activity": ".MainActivity"},
        "screen_width": 400,
        "screen_height": 800,
    }


def _macro_action(pre_state, action_type="tap"):
    action = {
        "action_type": action_type,
        "pre_state": pre_state,
        "recorded_at_ms": 1000,
        "elapsed_since_previous_ms": 0,
    }
    if action_type == "tap":
        action.update(
            {
                "x": 60,
                "y": 40,
            }
        )
    return action


def test_guarded_replay_waits_until_expected_state_appears_then_taps_saved_coordinates():
    saved_state = normalize_ui_state(_raw_state())
    player = MacroPlayer(
        delay_between_actions=0,
        state_timeout=0.5,
        state_poll_interval=0.01,
    )
    player.driver = FakeDriver(
        [_raw_state(text="Loading"), _raw_state(bounds="50,100,250,180")]
    )
    macro_data = {
        "macro_schema_version": "2.0",
        "description": "continue",
        "actions": [_macro_action(saved_state)],
    }

    success = asyncio.run(player.replay_macro(macro_data))

    assert success
    assert player.driver.taps == [(60, 40)]


def test_replay_ignores_stale_target_hint_and_uses_recorded_coordinates():
    saved_state = normalize_ui_state(_raw_state())
    action = _macro_action(saved_state)
    action["target_hint"] = saved_state["nodes"][0]
    player = MacroPlayer(
        delay_between_actions=0,
        state_timeout=0.5,
        state_poll_interval=0.01,
    )
    player.driver = FakeDriver([_raw_state(bounds="50,100,250,180")])
    macro_data = {
        "macro_schema_version": "2.0",
        "description": "continue",
        "actions": [action],
    }

    success = asyncio.run(player.replay_macro(macro_data))

    assert success
    assert player.driver.taps == [(60, 40)]


def test_stop_mode_aborts_on_state_mismatch_with_divergence_report():
    saved_state = normalize_ui_state(_raw_state())
    player = MacroPlayer(
        delay_between_actions=0,
        on_mismatch="stop",
        state_timeout=0.01,
        state_poll_interval=0.01,
    )
    player.driver = FakeDriver([_raw_state(text="Delete")])
    macro_data = {
        "macro_schema_version": "2.0",
        "description": "continue",
        "actions": [_macro_action(saved_state)],
    }

    success = asyncio.run(player.replay_macro(macro_data))

    assert not success
    assert player.driver.taps == []
    assert player.last_divergence["step"] == 1
    assert "below threshold" in player.last_divergence["reason"]


def test_agent_mode_hands_off_at_mismatch_and_does_not_resume_macro_replay():
    calls = []

    async def handoff(**kwargs):
        calls.append(kwargs)
        return True

    saved_state = normalize_ui_state(_raw_state())
    player = MacroPlayer(
        delay_between_actions=0,
        on_mismatch="agent",
        state_timeout=0.01,
        state_poll_interval=0.01,
        handoff_runner=handoff,
    )
    player.driver = FakeDriver([_raw_state(text="Delete")])
    macro_data = {
        "macro_schema_version": "2.0",
        "description": "finish task",
        "actions": [
            _macro_action(saved_state),
            {
                "action_type": "input_text",
                "text": "must-not-run",
                "pre_state": saved_state,
            },
        ],
    }

    success = asyncio.run(player.replay_macro(macro_data))

    assert success
    assert player.driver.taps == []
    assert player.driver.inputs == []
    assert len(calls) == 1
    assert calls[0]["goal"] == "finish task"
    assert calls[0]["divergence"]["step"] == 1
    assert calls[0]["remaining_actions"][0]["action_type"] == "tap"


def test_current_state_snapshot_prefers_state_provider_when_available():
    class FakeProvider:
        async def get_state(self):
            return SimpleNamespace(
                elements=[
                    {
                        "resourceId": "com.example:id/provider",
                        "className": "android.widget.TextView",
                        "text": "Provider formatted state",
                        "bounds": "0,0,100,100",
                    }
                ],
                phone_state={"package": "com.example", "activity": ".Main"},
                screen_width=100,
                screen_height=100,
            )

    player = MacroPlayer(delay_between_actions=0)
    player.driver = FakeDriver([_raw_state(text="Raw state")])
    player.state_provider = FakeProvider()

    snapshot = asyncio.run(player.get_current_state_snapshot())

    assert snapshot["nodes"][0]["text"] == "Provider formatted state"


def test_type_secret_replay_resolves_secret_without_logging_value():
    saved_state = normalize_ui_state(_raw_state())
    player = MacroPlayer(delay_between_actions=0)
    player.driver = FakeDriver([_raw_state()])
    player.credential_manager = FakeCredentialManager({"login_password": "s3cr3t"})
    macro_data = {
        "macro_schema_version": "2.0",
        "description": "type secret",
        "actions": [
            {
                "action_type": "type_secret",
                "secret_id": "login_password",
                "clear": False,
                "pre_state": saved_state,
            }
        ],
    }

    success = asyncio.run(player.replay_macro(macro_data))

    assert success
    assert player.driver.inputs == [("s3cr3t", False)]


def test_type_secret_replay_fails_without_typing_when_secret_missing():
    saved_state = normalize_ui_state(_raw_state())
    player = MacroPlayer(delay_between_actions=0)
    player.driver = FakeDriver([_raw_state()])
    player.credential_manager = FakeCredentialManager({})
    macro_data = {
        "macro_schema_version": "2.0",
        "description": "type secret",
        "actions": [
            {
                "action_type": "type_secret",
                "secret_id": "missing_password",
                "clear": False,
                "pre_state": saved_state,
            }
        ],
    }

    success = asyncio.run(player.replay_macro(macro_data))

    assert not success
    assert player.driver.inputs == []
