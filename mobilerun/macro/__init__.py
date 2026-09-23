"""Mobilerun Macro Module - Record and replay UI automation sequences."""

__all__ = ["MacroPlayer", "replay_macro_file", "replay_macro_folder"]


def __getattr__(name):
    if name in __all__:
        from mobilerun.macro import replay

        return getattr(replay, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
