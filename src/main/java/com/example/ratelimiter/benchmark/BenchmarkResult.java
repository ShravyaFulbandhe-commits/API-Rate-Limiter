package com.example.ratelimiter.benchmark;

import java.util.List;

public record BenchmarkResult(Scenario scenario, int[] offeredPerSecond, List<AlgorithmResult> results) {

    public record Scenario(int limit, long windowMs, long durationMs, int steadyRps, int burstSize, List<Long> burstTimes) {}

    /**
     * @param maxInAnyWindow the largest number of admitted requests inside any span of length windowMs.
     *                       A strict "N per window" limiter should never exceed N here.
     */
    public record AlgorithmResult(String algorithm, int offered, int allowed, int rejected,
                                  int maxInAnyWindow, int[] allowedPerSecond) {}
}
