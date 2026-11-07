package com.example.ratelimiter.core;

/** Factory / registry key for the available algorithms. */
public enum Algorithm {
    FIXED_WINDOW,
    SLIDING_WINDOW_LOG,
    SLIDING_WINDOW_COUNTER,
    TOKEN_BUCKET;

    public RateLimiter create(int limit, long windowMs, TimeSource time) {
        return switch (this) {
            case FIXED_WINDOW -> new FixedWindowLimiter(limit, windowMs, time);
            case SLIDING_WINDOW_LOG -> new SlidingWindowLogLimiter(limit, windowMs, time);
            case SLIDING_WINDOW_COUNTER -> new SlidingWindowCounterLimiter(limit, windowMs, time);
            case TOKEN_BUCKET -> new TokenBucketLimiter(limit, windowMs, time);
        };
    }
}
