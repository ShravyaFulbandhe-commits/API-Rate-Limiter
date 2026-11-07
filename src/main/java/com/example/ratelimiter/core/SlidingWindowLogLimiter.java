package com.example.ratelimiter.core;

import java.util.ArrayDeque;

/**
 * Sliding window log. Stores the timestamp of every admitted request and, on each call, discards
 * timestamps older than one window. A request is admitted only if fewer than {@code limit}
 * timestamps remain.
 *
 * <p>Pros: exact. In <i>any</i> span of length W, at most {@code limit} requests are admitted.<br>
 * Cons: O(limit) memory per key, so it gets expensive for high limits.
 */
public class SlidingWindowLogLimiter extends AbstractKeyedLimiter<ArrayDeque<Long>> {

    public SlidingWindowLogLimiter(int limit, long windowMs, TimeSource time) {
        super(limit, windowMs, time);
    }

    @Override
    public String name() {
        return "SLIDING_WINDOW_LOG";
    }

    @Override
    protected ArrayDeque<Long> newState(long now) {
        return new ArrayDeque<>();
    }

    @Override
    protected RateLimitResult acquire(ArrayDeque<Long> log, long now) {
        long cutoff = now - windowMs;
        while (!log.isEmpty() && log.peekFirst() <= cutoff) {
            log.pollFirst();
        }
        if (log.size() < limit) {
            log.addLast(now);
            return RateLimitResult.allowed(limit, limit - log.size());
        }
        return RateLimitResult.rejected(limit, log.peekFirst() + windowMs - now);
    }

    @Override
    protected boolean isIdle(ArrayDeque<Long> log, long now) {
        return log.isEmpty() || log.peekLast() <= now - windowMs;
    }
}
