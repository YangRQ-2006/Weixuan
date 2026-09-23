from __future__ import annotations

import logging
from functools import lru_cache

MINIMAX_GLOBAL_BASE_URL = "https://api.minimax.io/v1"
MINIMAX_CHINA_BASE_URL = "https://api.minimaxi.com/v1"
MINIMAX_LEGACY_BASE_URL = "https://api.minimaxi.chat/v1"

logger = logging.getLogger("mobilerun")


def _normalize_base_url(base_url: str | None) -> str:
    return str(base_url or "").strip().rstrip("/").lower()


@lru_cache(maxsize=1)
def _warn_about_legacy_endpoint_once() -> None:
    logger.warning(
        "This MiniMax profile uses the legacy endpoint "
        f"{MINIMAX_LEGACY_BASE_URL}. The profile was not changed. Re-run "
        "`mobilerun configure` and choose either Global "
        f"({MINIMAX_GLOBAL_BASE_URL}) or Mainland China "
        f"({MINIMAX_CHINA_BASE_URL})."
    )


def warn_if_legacy_minimax_endpoint(base_url: str | None) -> None:
    """Warn once per process when a profile still uses MiniMax's legacy endpoint."""
    if _normalize_base_url(base_url) == _normalize_base_url(MINIMAX_LEGACY_BASE_URL):
        _warn_about_legacy_endpoint_once()
