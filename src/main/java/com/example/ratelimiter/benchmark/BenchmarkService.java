package com.example.ratelimiter.benchmark;

import com.example.ratelimiter.benchmark.BenchmarkResult.AlgorithmResult;
import com.example.ratelimiter.benchmark.BenchmarkResult.Scenario;
import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.ManualClock;
import com.example.ratelimiter.core.RateLimiter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Replays the exact same arrival schedule through every algorithm on a virtual clock, so results
 * are deterministic and comparable (no sleeping, no scheduler noise).
 *
 * <p>Traffic = a steady trickle plus bursts at chosen instants. The default puts bursts just before
 * and just after a window boundary, which is where the algorithms behave most differently.
 */
@Service
public class BenchmarkService {

    private static final String KEY = "benchmark-user";

    public BenchmarkResult run(int limit, long windowMs, long durationMs, int steadyRps,
                               int burstSize, List<Long> burstTimes) {

        List<Long> arrivals = buildArrivals(durationMs, steadyRps, burstSize, burstTimes);
        int seconds = (int) Math.ceil(durationMs / 1000.0);

        int[] offeredPerSecond = new int[seconds];
        for (long t : arrivals) offeredPerSecond[(int) (t / 1000)]++;

        List<AlgorithmResult> results = new ArrayList<>();
        for (Algorithm algorithm : Algorithm.values()) {
            ManualClock clock = new ManualClock();
            RateLimiter limiter = algorithm.create(limit, windowMs, clock);

            int[] allowedPerSecond = new int[seconds];
            List<Long> allowedAt = new ArrayList<>();
            for (long t : arrivals) {
                clock.set(t);
                if (limiter.tryAcquire(KEY).allowed()) {
                    allowedPerSecond[(int) (t / 1000)]++;
                    allowedAt.add(t);
                }
            }
            results.add(new AlgorithmResult(
                    algorithm.name(), arrivals.size(), allowedAt.size(),
                    arrivals.size() - allowedAt.size(), maxInAnyWindow(allowedAt, windowMs), allowedPerSecond));
        }
        return new BenchmarkResult(new Scenario(limit, windowMs, durationMs, steadyRps, burstSize, burstTimes),
                offeredPerSecond, results);
    }

    private List<Long> buildArrivals(long durationMs, int steadyRps, int burstSize, List<Long> burstTimes) {
        List<Long> arrivals = new ArrayList<>();
        if (steadyRps > 0) {
            long interval = Math.max(1, 1000L / steadyRps);
            for (long t = 0; t < durationMs; t += interval) arrivals.add(t);
        }
        for (long burstAt : burstTimes) {
            if (burstAt < 0 || burstAt >= durationMs) continue;
            for (int i = 0; i < burstSize; i++) arrivals.add(burstAt);
        }
        Collections.sort(arrivals);
        return arrivals;
    }

    /** Two-pointer scan: max number of admitted timestamps inside any half-open span of length windowMs. */
    static int maxInAnyWindow(List<Long> sortedTimes, long windowMs) {
        int best = 0;
        int left = 0;
        for (int right = 0; right < sortedTimes.size(); right++) {
            while (sortedTimes.get(right) - sortedTimes.get(left) >= windowMs) left++;
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
