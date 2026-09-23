"""Preserve 'python -m droidrun' entrypoint."""

import warnings

warnings.warn(
    "Use 'python -m mobilerun' instead of 'python -m droidrun'.",
    DeprecationWarning,
    stacklevel=2,
)
from mobilerun.cli.main import cli  # noqa: E402

cli()
