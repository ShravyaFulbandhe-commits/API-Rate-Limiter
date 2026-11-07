package com.example.ratelimiter.core;

/** Strategy interface: every algorithm implements this, and callers never know which one they got. */
public interface RateLimiter {

    /** Atomically checks and (if allowed) consumes one unit of quota for the given key. */
    RateLimitResult tryAcquire(String key);

    /** Human-readable algorithm name. */
    String name();

    /** Drops state for keys that have been idle long enough that their state no longer matters. */
    void evictIdle();

    /** Number of keys currently holding state (useful for monitoring and tests). */
    int trackedKeys();
}
