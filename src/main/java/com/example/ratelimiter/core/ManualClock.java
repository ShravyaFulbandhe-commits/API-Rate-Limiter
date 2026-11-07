package com.example.ratelimiter.core;

import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when you tell it to. Used by tests and the benchmark. */
public class ManualClock implements TimeSource {

    private final AtomicLong now = new AtomicLong();

    public ManualClock() {
        this(0);
    }

    public ManualClock(long startMillis) {
        now.set(startMillis);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void set(long millis) {
        now.set(millis);
    }

    public void advance(long millis) {
        now.addAndGet(millis);
    }
}
