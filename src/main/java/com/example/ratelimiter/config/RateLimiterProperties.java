package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Algorithm;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterProperties {

    private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
    private int limit = 10;
    private long windowSeconds = 10;
    private long cleanupIntervalSeconds = 60;

    public Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Algorithm algorithm) { this.algorithm = algorithm; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }

    public long getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(long windowSeconds) { this.windowSeconds = windowSeconds; }

    public long getCleanupIntervalSeconds() { return cleanupIntervalSeconds; }
    public void setCleanupIntervalSeconds(long cleanupIntervalSeconds) { this.cleanupIntervalSeconds = cleanupIntervalSeconds; }
}
