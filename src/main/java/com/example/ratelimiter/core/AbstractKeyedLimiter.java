package com.example.ratelimiter.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Template for per-key limiters.
 *
 * <p><b>Concurrency model:</b> all state for one key lives in a mutable state object, and every
 * read-modify-write on it happens inside {@link ConcurrentHashMap#compute}. compute() locks only the
 * hash bin holding that key, so:
 * <ul>
 *   <li>two threads hitting the <i>same</i> key are serialised (no lost updates, no over-admission);</li>
 *   <li>threads hitting <i>different</i> keys run in parallel (except on rare bin collisions);</li>
 *   <li>idle-key eviction uses computeIfPresent on the same lock, so it can never race with a request
 *       and silently drop a count.</li>
 * </ul>
 * Because the critical section is a few arithmetic operations, this beats a global lock by a wide
 * margin and is much simpler than hand-rolled CAS loops.
 */
public abstract class AbstractKeyedLimiter<S> implements RateLimiter {

    protected final int limit;
    protected final long windowMs;
    protected final TimeSource time;
    private final ConcurrentHashMap<String, S> states = new ConcurrentHashMap<>();

    protected AbstractKeyedLimiter(int limit, long windowMs, TimeSource time) {
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        if (windowMs < 1) throw new IllegalArgumentException("windowMs must be >= 1");
        this.limit = limit;
        this.windowMs = windowMs;
        this.time = time;
    }

    @Override
    public final RateLimitResult tryAcquire(String key) {
        final long now = time.nowMillis();
        final RateLimitResult[] out = new RateLimitResult[1];
        states.compute(key, (k, existing) -> {
            S state = (existing != null) ? existing : newState(now);
            out[0] = acquire(state, now);
            return state;
        });
        return out[0];
    }

    @Override
    public final void evictIdle() {
        final long now = time.nowMillis();
        for (String key : states.keySet()) {
            states.computeIfPresent(key, (k, state) -> isIdle(state, now) ? null : state);
        }
    }

    @Override
    public final int trackedKeys() {
        return states.size();
    }

    /** Fresh state for a never-seen key. Called inside compute(). */
    protected abstract S newState(long now);

    /** Decide and update state. Called inside compute(), so it is already exclusive for this key. */
    protected abstract RateLimitResult acquire(S state, long now);

    /** True if this state is equivalent to a brand-new one and can be forgotten. */
    protected abstract boolean isIdle(S state, long now);
}
