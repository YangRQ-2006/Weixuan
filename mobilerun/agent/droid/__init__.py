"""
Mobilerun Agent Module.

This module provides a ReAct agent for automating Android devices using reasoning and acting.
"""

from mobilerun.agent.droid.droid_agent import MobileAgent
from mobilerun.agent.droid.state import MobileAgentState

# Legacy aliases for backward compatibility
_LEGACY_ALIASES = {
    "DroidAgent": MobileAgent,
    "DroidAgentState": MobileAgentState,
}


def __getattr__(name):
    if name in _LEGACY_ALIASES:
        import warnings

        warnings.warn(
            f"{name} has been renamed. Update your imports.",
            DeprecationWarning,
            stacklevel=2,
        )
        return _LEGACY_ALIASES[name]
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


__all__ = ["MobileAgent", "MobileAgentState", "DroidAgent", "DroidAgentState"]
