package com.example.ratelimiter.core;

/**
 * Sliding window counter (a.k.a. weighted / approximated sliding window). Keeps just two counters,
 * for the current and previous fixed window, and estimates the rolling count as
 * <pre>  estimate = previous * (1 - fractionOfCurrentWindowElapsed) + current</pre>
 *
 * <p>Pros: O(1) memory like fixed window, but smooths out the boundary burst.<br>
 * Cons: an approximation (it assumes the previous window's traffic was evenly spread).
 */
public class SlidingWindowCounterLimiter extends AbstractKeyedLimiter<SlidingWindowCounterLimiter.State> {

    static final class State {
        long id = Long.MIN_VALUE;
        int current;
        int previous;
        long lastSeen;
    }

    public SlidingWindowCounterLimiter(int limit, long windowMs, TimeSource time) {
        super(limit, windowMs, time);
    }

    @Override
    public String name() {
        return "SLIDING_WINDOW_COUNTER";
    }

    @Override
    protected State newState(long now) {
        return new State();
    }

    @Override
    protected RateLimitResult acquire(State s, long now) {
        long id = Math.floorDiv(now, windowMs);
        if (id != s.id) {
            // Only carry the old counter over if we moved exactly one window forward.
            s.previous = (id == s.id + 1) ? s.current : 0;
            s.current = 0;
            s.id = id;
        }
        s.lastSeen = now;

        long intoWindow = now - id * windowMs;
        double fraction = intoWindow / (double) windowMs;
        double estimate = s.previous * (1.0 - fraction) + s.current;

        if (estimate + 1.0 <= limit + 1e-9) {
            s.current++;
            int remaining = (int) Math.floor(limit - (estimate + 1.0) + 1e-9);
            return RateLimitResult.allowed(limit, remaining);
        }

        long toBoundary = (id + 1) * windowMs - now;
        long retry = toBoundary;
        if (s.previous > 0) {
            double excess = estimate + 1.0 - limit;
            long untilEstimateDrops = (long) Math.ceil(excess * windowMs / s.previous);
            retry = Math.min(toBoundary, untilEstimateDrops);
        }
        return RateLimitResult.rejected(limit, retry);
    }

    @Override
    protected boolean isIdle(State s, long now) {
        return now - s.lastSeen >= 2 * windowMs;
    }
}
