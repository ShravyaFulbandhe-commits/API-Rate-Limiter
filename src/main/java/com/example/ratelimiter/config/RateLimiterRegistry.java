package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimiter;
import com.example.ratelimiter.core.TimeSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Holds one instance of every algorithm and knows which one is currently live.
 * Switching is a single volatile write, so it is safe while requests are in flight.
 * A background scheduled task evicts idle keys so memory doesn't grow without bound.
 */
@Component
public class RateLimiterRegistry {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterRegistry.class);

    private final Map<Algorithm, RateLimiter> limiters = new EnumMap<>(Algorithm.class);
    private final ScheduledExecutorService janitor;
    private volatile Algorithm active;

    public RateLimiterRegistry(RateLimiterProperties props) {
        long windowMs = props.getWindowSeconds() * 1000L;
        for (Algorithm a : Algorithm.values()) {
            limiters.put(a, a.create(props.getLimit(), windowMs, TimeSource.SYSTEM));
        }
        this.active = props.getAlgorithm();

        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-janitor");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(1, props.getCleanupIntervalSeconds());
        janitor.scheduleWithFixedDelay(this::evictAll, interval, interval, TimeUnit.SECONDS);
        log.info("Rate limiter ready: algorithm={}, limit={} per {}s", active, props.getLimit(), props.getWindowSeconds());
    }

    public RateLimiter active() {
        return limiters.get(active);
    }

    public Algorithm activeAlgorithm() {
        return active;
    }

    public void setActive(Algorithm algorithm) {
        this.active = algorithm;
        log.info("Switched rate limiting algorithm to {}", algorithm);
    }

    private void evictAll() {
        try {
            limiters.values().forEach(RateLimiter::evictIdle);
        } catch (RuntimeException e) {
            log.warn("Idle-key eviction failed", e); // never let the scheduled task die
        }
    }

    @PreDestroy
    void shutdown() {
        janitor.shutdownNow();
    }
}
