package com.example.ratelimiter.core;

/**
 * Abstraction over "what time is it" so algorithms can be tested and benchmarked
 * deterministically with a virtual clock instead of Thread.sleep().
 */
@FunctionalInterface
public interface TimeSource {

    long nowMillis();

    /** Monotonic wall-clock-independent time (immune to NTP adjustments). */
    TimeSource SYSTEM = () -> System.nanoTime() / 1_000_000L;
}
