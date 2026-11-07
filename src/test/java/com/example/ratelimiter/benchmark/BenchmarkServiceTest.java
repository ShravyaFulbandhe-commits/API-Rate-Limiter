package com.example.ratelimiter.benchmark;

import com.example.ratelimiter.benchmark.BenchmarkResult.AlgorithmResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkServiceTest {

    private final BenchmarkService service = new BenchmarkService();

    private BenchmarkResult runDefault() {
        return service.run(100, 10_000, 30_000, 5, 100, List.of(9_900L, 10_100L, 20_000L));
    }

    private static AlgorithmResult find(BenchmarkResult r, String name) {
        return r.results().stream().filter(a -> a.algorithm().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everyAlgorithmSeesTheSameTrafficAndAccountsForAllOfIt() {
        BenchmarkResult result = runDefault();
        int offered = result.results().get(0).offered();
        for (AlgorithmResult a : result.results()) {
            assertEquals(offered, a.offered());
            assertEquals(a.offered(), a.allowed() + a.rejected());
        }
    }

    @Test
    void fixedWindowBreaksTheLimitAtTheBoundaryButSlidingLogDoesNot() {
        BenchmarkResult result = runDefault();
        int limit = result.scenario().limit();

        assertTrue(find(result, "FIXED_WINDOW").maxInAnyWindow() > limit,
                "fixed window should admit > limit across the window boundary");
        assertTrue(find(result, "SLIDING_WINDOW_LOG").maxInAnyWindow() <= limit,
                "sliding log is exact");
    }

    @Test
    void maxInAnyWindowScan() {
        assertEquals(3, BenchmarkService.maxInAnyWindow(List.of(0L, 10L, 20L, 1000L, 2000L), 1000));
        assertEquals(0, BenchmarkService.maxInAnyWindow(List.of(), 1000));
    }
}
