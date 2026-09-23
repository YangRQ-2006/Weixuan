"""Safe shared storage for Mobilerun authentication profiles.

All OAuth providers and saved API keys share one JSON object.  Writes therefore
need to be serialized across processes and must preserve slots owned by other
providers.
"""

from __future__ import annotations

import json
import os
import tempfile
from contextlib import AbstractContextManager
from pathlib import Path
from typing import Any, Callable, TypeVar

from filelock import FileLock


class AuthProfileFormatError(ValueError):
    """Raised when an existing auth-profiles file is not a JSON object."""


_T = TypeVar("_T")
_FCHMOD: Callable[[int, int], None] | None = getattr(os, "fchmod", None)


class AuthProfileTransaction(AbstractContextManager["AuthProfileTransaction"]):
    """A locked read/modify/write transaction over an auth profile file."""

    def __init__(
        self,
        store: "AuthProfileStore",
        *,
        lock_timeout: float | None = None,
        before_commit: Callable[[], None] | None = None,
    ) -> None:
        self._store = store
        self._lock = FileLock(str(store.lock_path))
        self._lock_timeout = lock_timeout
        self._before_commit = before_commit
        self._profile: dict[str, Any] = {}
        self._dirty = False

    def __enter__(self) -> "AuthProfileTransaction":
        self._store.path.parent.mkdir(parents=True, exist_ok=True)
        if self._lock_timeout is None:
            self._lock.acquire()
        else:
            self._lock.acquire(timeout=self._lock_timeout)
        try:
            # The lock file has no secrets, but keeping it private prevents
            # other local users from deliberately interfering with writers.
            try:
                os.chmod(self._store.lock_path, 0o600)
            except OSError:
                pass
            self._profile = self._store._read_unlocked()
        except BaseException:
            self._lock.release()
            raise
        return self

    @property
    def profile(self) -> dict[str, Any]:
        """The locked profile object. Mutate it only via :meth:`update`."""
        return self._profile

    def get_slot(self, slot: str) -> dict[str, Any] | None:
        value = self._profile.get(slot)
        return dict(value) if isinstance(value, dict) else None

    def set_slot(self, slot: str, payload: dict[str, Any]) -> None:
        self._profile[slot] = dict(payload)
        self._dirty = True

    def update(self, updater: Callable[[dict[str, Any]], _T]) -> _T:
        result = updater(self._profile)
        self._dirty = True
        return result

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        try:
            if exc_type is None and self._dirty:
                self._store._write_unlocked(
                    self._profile,
                    before_commit=self._before_commit,
                )
        finally:
            self._lock.release()
        return None


class AuthProfileStore:
    """Cross-process-safe JSON object store used by all auth providers."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path).expanduser()
        self.lock_path = self.path.with_name(f"{self.path.name}.lock")

    def transaction(
        self,
        *,
        lock_timeout: float | None = None,
        before_commit: Callable[[], None] | None = None,
    ) -> AuthProfileTransaction:
        return AuthProfileTransaction(
            self,
            lock_timeout=lock_timeout,
            before_commit=before_commit,
        )

    def read_profile(self) -> dict[str, Any]:
        with self.transaction() as transaction:
            return dict(transaction.profile)

    def read_slot(self, slot: str) -> dict[str, Any] | None:
        with self.transaction() as transaction:
            return transaction.get_slot(slot)

    def update_slot(
        self,
        slot: str,
        payload: dict[str, Any],
        *,
        lock_timeout: float | None = None,
        before_commit: Callable[[], None] | None = None,
    ) -> None:
        with self.transaction(
            lock_timeout=lock_timeout,
            before_commit=before_commit,
        ) as transaction:
            transaction.set_slot(slot, payload)

    def update_profile(self, updater: Callable[[dict[str, Any]], _T]) -> _T:
        with self.transaction() as transaction:
            return transaction.update(updater)

    def _read_unlocked(self) -> dict[str, Any]:
        if not self.path.exists():
            return {}
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise AuthProfileFormatError(
                f"Cannot update malformed authentication profile {self.path}. "
                "Repair or remove the file, then retry."
            ) from exc
        if not isinstance(payload, dict):
            raise AuthProfileFormatError(
                f"Authentication profile {self.path} must contain a JSON object."
            )
        return payload

    def _write_unlocked(
        self,
        profile: dict[str, Any],
        *,
        before_commit: Callable[[], None] | None = None,
    ) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd: int | None = None
        tmp_path: Path | None = None
        try:
            fd, raw_tmp_path = tempfile.mkstemp(
                dir=self.path.parent,
                prefix=f".{self.path.name}.",
                suffix=".tmp",
            )
            tmp_path = Path(raw_tmp_path)
            if _FCHMOD is not None:
                _FCHMOD(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                fd = None
                json.dump(profile, handle, indent=2)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            if before_commit is not None:
                before_commit()
            os.replace(tmp_path, self.path)
            tmp_path = None
            os.chmod(self.path, 0o600)

            # Persist the directory entry as well as the file contents.
            try:
                directory_fd = os.open(self.path.parent, os.O_RDONLY)
            except OSError:
                directory_fd = None
            if directory_fd is not None:
                try:
                    os.fsync(directory_fd)
                finally:
                    os.close(directory_fd)
        finally:
            if fd is not None:
                os.close(fd)
            if tmp_path is not None:
                try:
                    tmp_path.unlink()
                except FileNotFoundError:
                    pass
