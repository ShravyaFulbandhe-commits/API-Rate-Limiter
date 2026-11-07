package com.example.ratelimiter.core;

/**
 * Outcome of a single tryAcquire call.
 *
 * @param allowed           whether the request may proceed
 * @param limit             the configured maximum per window
 * @param remaining         how many more requests are (roughly) available right now
 * @param retryAfterMillis  when rejected, how long the caller should wait before retrying
 */
public record RateLimitResult(boolean allowed, int limit, int remaining, long retryAfterMillis) {

    public static RateLimitResult allowed(int limit, int remaining) {
        return new RateLimitResult(true, limit, Math.max(0, remaining), 0);
    }

    public static RateLimitResult rejected(int limit, long retryAfterMillis) {
        return new RateLimitResult(false, limit, 0, Math.max(1, retryAfterMillis));
    }
}
