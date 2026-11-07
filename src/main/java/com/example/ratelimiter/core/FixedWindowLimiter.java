package com.example.ratelimiter.core;

/**
 * Fixed window counter. Time is cut into aligned windows [0,W), [W,2W) ... and each key gets a
 * counter that resets at every boundary.
 *
 * <p>Pros: O(1) memory and time, trivial to implement (and to do in Redis with INCR + EXPIRE).<br>
 * Cons: boundary burst. A client can send {@code limit} requests at the very end of one window and
 * another {@code limit} at the start of the next, i.e. up to 2x the limit in a very short span.
 */
public class FixedWindowLimiter extends AbstractKeyedLimiter<FixedWindowLimiter.Window> {

    static final class Window {
        long id = Long.MIN_VALUE;
        int count;
        long lastSeen;
    }

    public FixedWindowLimiter(int limit, long windowMs, TimeSource time) {
        super(limit, windowMs, time);
    }

    @Override
    public String name() {
        return "FIXED_WINDOW";
    }

    @Override
    protected Window newState(long now) {
        return new Window();
    }

    @Override
    protected RateLimitResult acquire(Window w, long now) {
        long id = Math.floorDiv(now, windowMs);
        if (id != w.id) {
            w.id = id;
            w.count = 0;
        }
        w.lastSeen = now;
        if (w.count < limit) {
            w.count++;
            return RateLimitResult.allowed(limit, limit - w.count);
        }
        return RateLimitResult.rejected(limit, (id + 1) * windowMs - now);
    }

    @Override
    protected boolean isIdle(Window w, long now) {
        return now - w.lastSeen >= windowMs;
    }
}
