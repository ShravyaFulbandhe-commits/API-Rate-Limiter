package com.example.ratelimiter.core;

/**
 * Token bucket. Each key owns a bucket holding up to {@code limit} tokens that refills continuously
 * at {@code limit / window} tokens per millisecond. Every request consumes one token.
 *
 * <p>Pros: O(1) memory, allows controlled bursts (up to the bucket size) while enforcing the
 * long-run average rate. This is what most public APIs (Stripe, AWS, ...) use.<br>
 * Cons: because a full bucket can be drained at once, a span of length W can admit up to
 * {@code limit + limit} requests (bucket + refill) in the worst case.
 */
public class TokenBucketLimiter extends AbstractKeyedLimiter<TokenBucketLimiter.Bucket> {

    static final class Bucket {
        double tokens;
        long lastRefill;
    }

    private final double refillPerMs;

    public TokenBucketLimiter(int limit, long windowMs, TimeSource time) {
        super(limit, windowMs, time);
        this.refillPerMs = limit / (double) windowMs;
    }

    @Override
    public String name() {
        return "TOKEN_BUCKET";
    }

    @Override
    protected Bucket newState(long now) {
        Bucket b = new Bucket();
        b.tokens = limit; // new clients start with a full bucket
        b.lastRefill = now;
        return b;
    }

    @Override
    protected RateLimitResult acquire(Bucket b, long now) {
        long elapsed = Math.max(0, now - b.lastRefill);
        b.tokens = Math.min(limit, b.tokens + elapsed * refillPerMs);
        b.lastRefill = now;

        if (b.tokens >= 1.0 - 1e-9) {
            b.tokens = Math.max(0.0, b.tokens - 1.0);
            return RateLimitResult.allowed(limit, (int) Math.floor(b.tokens + 1e-9));
        }
        long wait = (long) Math.ceil((1.0 - b.tokens) / refillPerMs);
        return RateLimitResult.rejected(limit, wait);
    }

    @Override
    protected boolean isIdle(Bucket b, long now) {
        // After one full window of silence the bucket is guaranteed full, i.e. identical to a new one.
        return now - b.lastRefill >= windowMs;
    }
}
