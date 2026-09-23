"""Action-level macro recorder for schema v2."""

from __future__ import annotations

import time
from typing import Any, Dict, List, Optional

from mobilerun.macro.state import build_screen_snapshot


class MacroRecorder:
    def __init__(self) -> None:
        self.actions: List[Dict[str, Any]] = []
        self._last_recorded_at_ms: Optional[int] = None

    def record_action(
        self,
        action: Dict[str, Any],
        *,
        pre_ui: Any = None,
        post_ui: Any = None,
    ) -> Dict[str, Any]:
        now_ms = int(time.time() * 1000)
        elapsed_ms = (
            0
            if self._last_recorded_at_ms is None
            else max(0, now_ms - self._last_recorded_at_ms)
        )

        snapshot = build_screen_snapshot(pre_ui)
        macro_action = {
            **action,
            "pre_state": snapshot,
            "screen": snapshot.get("screen", {}),
            "recorded_at_ms": now_ms,
            "elapsed_since_previous_ms": elapsed_ms,
        }

        if post_ui is not None:
            macro_action["post_state"] = build_screen_snapshot(post_ui)

        self.actions.append(macro_action)
        self._last_recorded_at_ms = now_ms
        return macro_action

    def record_wait(self, duration: float, *, pre_ui: Any = None, post_ui: Any = None):
        return self.record_action(
            {"action_type": "wait", "duration": duration},
            pre_ui=pre_ui,
            post_ui=post_ui,
        )
