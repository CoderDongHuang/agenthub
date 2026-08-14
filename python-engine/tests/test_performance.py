import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent_runtime.core.performance import BoundedTTLCache, KeyedConcurrencyLimiter, RuntimeMetrics


def test_bounded_ttl_cache_evicts_lru_entry():
    cache = BoundedTTLCache(max_size=2, ttl_seconds=60)
    cache.get_or_create("a", lambda: 1)
    cache.get_or_create("b", lambda: 2)
    assert cache.get("a") == 1
    cache.get_or_create("c", lambda: 3)
    assert cache.get("b") is None
    assert len(cache) == 2


def test_bounded_ttl_cache_expires_entries():
    cache = BoundedTTLCache(max_size=2, ttl_seconds=0.01)
    cache.get_or_create("a", lambda: 1)
    time.sleep(0.02)
    assert cache.get("a") is None


def test_keyed_concurrency_limiter_caps_parallel_work():
    async def run():
        limiter = KeyedConcurrencyLimiter(limit=2)
        active = 0
        peak = 0

        async def task():
            nonlocal active, peak
            async with limiter.slot("tenant:model"):
                active += 1
                peak = max(peak, active)
                await asyncio.sleep(0.01)
                active -= 1

        await asyncio.gather(*(task() for _ in range(6)))
        return peak

    assert asyncio.run(run()) == 2


def test_runtime_metrics_snapshot():
    metrics = RuntimeMetrics()
    metrics.observe_first_token(10)
    metrics.observe_request(30, error=True)
    metrics.increment_retry("provider")
    assert metrics.snapshot() == {
        "requests": 1,
        "errors": 1,
        "cancelled": 0,
        "average_latency_ms": 30.0,
        "average_first_token_ms": 10.0,
        "retries": {"provider": 1},
    }
