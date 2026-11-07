package com.example.ratelimiter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterAlgorithmsTest {

    private static final long WINDOW = 1000;

    // ---------- behaviour shared by every algorithm ----------

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void allowsExactlyLimitThenRejectsWithRetryHint(Algorithm algorithm) {
        RateLimiter limiter = algorithm.create(5, WINDOW, new ManualClock());

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("u").allowed(), "request " + i + " should pass");
        }
        RateLimitResult rejected = limiter.tryAcquire("u");
        assertFalse(rejected.allowed());
        assertEquals(0, rejected.remaining());
        assertTrue(rejected.retryAfterMillis() > 0, "rejection must say when to retry");
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void keysAreIndependent(Algorithm algorithm) {
        RateLimiter limiter = algorithm.create(2, WINDOW, new ManualClock());
        assertTrue(limiter.tryAcquire("alice").allowed());
        assertTrue(limiter.tryAcquire("alice").allowed());
        assertFalse(limiter.tryAcquire("alice").allowed());

        assertTrue(limiter.tryAcquire("bob").allowed(), "bob must not be affected by alice");
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void quotaComesBackAfterIdleTime(Algorithm algorithm) {
        ManualClock clock = new ManualClock();
        RateLimiter limiter = algorithm.create(3, WINDOW, clock);
        for (int i = 0; i < 3; i++) limiter.tryAcquire("u");
        assertFalse(limiter.tryAcquire("u").allowed());

        clock.advance(2 * WINDOW);

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("u").allowed(), "full quota should be restored, request " + i);
        }
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void idleKeysAreEvicted(Algorithm algorithm) {
        ManualClock clock = new ManualClock();
        RateLimiter limiter = algorithm.create(3, WINDOW, clock);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");
        assertEquals(3, limiter.trackedKeys());

        clock.advance(3 * WINDOW);
        limiter.evictIdle();

        assertEquals(0, limiter.trackedKeys());
    }

    // ---------- concurrency: the part interviewers care about ----------

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void manyThreadsOneKeyNeverOverAdmit(Algorithm algorithm) throws Exception {
        // Clock is frozen so no refill / window roll can happen: exactly `limit` must get through.
        RateLimiter limiter = algorithm.create(100, WINDOW, new ManualClock());

        Map<String, Long> allowed = hammer(limiter, 16, 500, i -> "shared");

        assertEquals(100L, allowed.get("shared"),
                algorithm + " admitted a different number than the limit under contention");
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void manyThreadsManyKeysEachGetExactlyTheirQuota(Algorithm algorithm) throws Exception {
        RateLimiter limiter = algorithm.create(10, WINDOW, new ManualClock());

        Map<String, Long> allowed = hammer(limiter, 8, 500, i -> "user-" + (i % 50));

        assertEquals(50, allowed.size());
        allowed.forEach((key, count) -> assertEquals(10L, count, key));
        assertEquals(50, limiter.trackedKeys());
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void concurrentEvictionDoesNotLoseOrCorruptState(Algorithm algorithm) throws Exception {
        // A janitor thread evicts continuously while workers hammer the limiter on a frozen clock.
        // Frozen clock => nothing is ever idle => eviction must be a no-op and the count must stay exact.
        RateLimiter limiter = algorithm.create(100, WINDOW, new ManualClock());
        ExecutorService janitor = Executors.newSingleThreadExecutor();
        Future<?> evicting = janitor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) limiter.evictIdle();
        });
        try {
            Map<String, Long> allowed = hammer(limiter, 8, 1000, i -> "k");
            assertEquals(100L, allowed.get("k"));
        } finally {
            evicting.cancel(true);
            janitor.shutdownNow();
        }
    }

    // ---------- algorithm-specific behaviour ----------

    @Test
    void fixedWindowAllowsBoundaryBurst() {
        ManualClock clock = new ManualClock();
        RateLimiter limiter = new FixedWindowLimiter(10, WINDOW, clock);

        clock.set(999);                       // last millisecond of window 0
        assertEquals(10, countAllowed(limiter, 10));
        clock.set(1000);                      // first millisecond of window 1
        assertEquals(10, countAllowed(limiter, 10), "fixed window resets at the boundary: 20 requests in 2 ms");
    }

    @Test
    void slidingWindowLogHasNoBoundaryBurst() {
        ManualClock clock = new ManualClock();
        RateLimiter limiter = new SlidingWindowLogLimiter(10, WINDOW, clock);

        clock.set(999);
        assertEquals(10, countAllowed(limiter, 10));
        clock.set(1000);
        assertEquals(0, countAllowed(limiter, 10), "the 10 requests from 1 ms ago still count");
        clock.set(1999);                      // 999 + 1000 -> the old entries have just expired
        assertEquals(10, countAllowed(limiter, 10));
    }

    @Test
    void slidingWindowCounterWeightsThePreviousWindow() {
        ManualClock clock = new ManualClock();
        RateLimiter limiter = new SlidingWindowCounterLimiter(10, WINDOW, clock);

        clock.set(900);
        assertEquals(10, countAllowed(limiter, 10));   // window 0 is now full
        clock.set(1500);                               // halfway through window 1: 10 * 0.5 = 5 still counted
        assertEquals(5, countAllowed(limiter, 10));
    }

    @Test
    void tokenBucketRefillsContinuously() {
        ManualClock clock = new ManualClock();
        RateLimiter limiter = new TokenBucketLimiter(10, WINDOW, clock); // 1 token / 100 ms
        assertEquals(10, countAllowed(limiter, 20));   // drain the bucket

        clock.advance(100);
        assertEquals(1, countAllowed(limiter, 5), "exactly one token should have refilled");

        clock.advance(300);
        assertEquals(3, countAllowed(limiter, 5));
    }

    @Test
    void tokenBucketNeverExceedsCapacityAfterLongIdle() {
        ManualClock clock = new ManualClock();
        RateLimiter limiter = new TokenBucketLimiter(10, WINDOW, clock);
        countAllowed(limiter, 10);
        clock.advance(1_000_000);
        assertEquals(10, countAllowed(limiter, 50), "bucket is capped at its capacity");
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowLimiter(0, WINDOW, new ManualClock()));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(5, 0, new ManualClock()));
    }

    // ---------- helpers ----------

    private static int countAllowed(RateLimiter limiter, int attempts) {
        int ok = 0;
        for (int i = 0; i < attempts; i++) if (limiter.tryAcquire("u").allowed()) ok++;
        return ok;
    }

    /** Releases all threads at once through a latch, then returns allowed-count per key. */
    private static Map<String, Long> hammer(RateLimiter limiter, int threads, int callsPerThread,
                                            IntFunction<String> keyFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentHashMap<String, LongAdder> allowed = new ConcurrentHashMap<>();
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        String key = keyFor.apply(i);
                        if (limiter.tryAcquire(key).allowed()) {
                            allowed.computeIfAbsent(key, k -> new LongAdder()).increment();
                        }
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Void> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        Map<String, Long> result = new ConcurrentHashMap<>();
        allowed.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }
}
