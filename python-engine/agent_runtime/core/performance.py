"""Bounded runtime caches, concurrency limits, and lightweight metrics."""
import asyncio
import time
from collections import OrderedDict, defaultdict
from contextlib import asynccontextmanager
from threading import Lock, RLock
from typing import Callable, Dict, Generic, Hashable, Optional, TypeVar

K = TypeVar("K", bound=Hashable)
V = TypeVar("V")


class BoundedTTLCache(Generic[K, V]):
    def __init__(self, max_size: int, ttl_seconds: float):
        self.max_size = max(1, max_size)
        self.ttl_seconds = max(0.01, ttl_seconds)
        self._entries: "OrderedDict[K, tuple[float, V]]" = OrderedDict()
        self._lock = RLock()

    def get(self, key: K) -> Optional[V]:
        now = time.monotonic()
        with self._lock:
            entry = self._entries.pop(key, None)
            if entry is None:
                return None
            expires_at, value = entry
            if expires_at <= now:
                return None
            self._entries[key] = entry
            return value

    def get_or_create(self, key: K, factory: Callable[[], V]) -> V:
        value = self.get(key)
        if value is not None:
            return value
        value = factory()
        with self._lock:
            self._entries[key] = (time.monotonic() + self.ttl_seconds, value)
            self._entries.move_to_end(key)
            while len(self._entries) > self.max_size:
                self._entries.popitem(last=False)
        return value

    def __len__(self) -> int:
        with self._lock:
            return len(self._entries)


class KeyedConcurrencyLimiter:
    def __init__(self, limit: int, max_keys: int = 256):
        self.limit = max(1, limit)
        self.max_keys = max(1, max_keys)
        self._entries: "OrderedDict[str, dict]" = OrderedDict()
        self._lock = asyncio.Lock()

    @asynccontextmanager
    async def slot(self, key: str):
        async with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                entry = {"semaphore": asyncio.Semaphore(self.limit), "active": 0}
                self._entries[key] = entry
            self._entries.move_to_end(key)
            entry["active"] += 1
            self._evict_idle_entries()
        try:
            async with entry["semaphore"]:
                yield
        finally:
            async with self._lock:
                entry["active"] -= 1
                self._evict_idle_entries()

    def _evict_idle_entries(self) -> None:
        if len(self._entries) <= self.max_keys:
            return
        for key, entry in list(self._entries.items()):
            if entry["active"] == 0:
                del self._entries[key]
                if len(self._entries) <= self.max_keys:
                    break


class RuntimeMetrics:
    def __init__(self):
        self._lock = Lock()
        self._requests = 0
        self._errors = 0
        self._cancelled = 0
        self._retries: Dict[str, int] = defaultdict(int)
        self._total_latency_ms = 0.0
        self._first_token_total_ms = 0.0
        self._first_token_samples = 0

    def observe_request(self, latency_ms: float, *, error: bool = False, cancelled: bool = False) -> None:
        with self._lock:
            self._requests += 1
            self._total_latency_ms += latency_ms
            self._errors += int(error)
            self._cancelled += int(cancelled)

    def observe_first_token(self, latency_ms: float) -> None:
        with self._lock:
            self._first_token_samples += 1
            self._first_token_total_ms += latency_ms

    def increment_retry(self, operation: str) -> None:
        with self._lock:
            self._retries[operation] += 1

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "requests": self._requests,
                "errors": self._errors,
                "cancelled": self._cancelled,
                "average_latency_ms": round(self._total_latency_ms / self._requests, 2) if self._requests else 0,
                "average_first_token_ms": round(self._first_token_total_ms / self._first_token_samples, 2)
                if self._first_token_samples else 0,
                "retries": dict(self._retries),
            }


runtime_metrics = RuntimeMetrics()
