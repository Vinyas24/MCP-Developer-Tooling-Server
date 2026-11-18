package com.mcpdev.tooling.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token bucket per (identity, tool). Capacity caps bursts, refill caps sustained
 * rate. Honest scope note from the design: sync dispatch serializes calls per stdio
 * session, so this guards CPU-heavy tools (search_code) and future async clients;
 * its teeth fully arrive with the Phase 6 executor.
 */
public class RateLimitGuard implements Guard {

    /** Test seam: acquire n tokens atomically. */
    public static final class Bucket {
        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastRefill;
        private final ReentrantLock lock = new ReentrantLock();

        Bucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
            this.lastRefill = System.nanoTime();
        }

        boolean tryAcquire() {
            lock.lock();
            try {
                long now = System.nanoTime();
                tokens = Math.min(capacity,
                        tokens + (now - lastRefill) / 1_000_000_000.0 * refillPerSecond);
                lastRefill = now;
                if (tokens >= 1) {
                    tokens -= 1;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double refillPerSecond;

    public RateLimitGuard(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @Override
    public Result check(GuardContext context) {
        String key = context.identity().name() + "|" + context.tool().name();
        Bucket bucket = buckets.computeIfAbsent(key,
                k -> new Bucket(capacity, refillPerSecond));
        if (!bucket.tryAcquire()) {
            return Result.deny(AuditLog.Outcome.DENIED_RATE,
                    "denied: rate limit exceeded for tool '" + context.tool().name() + "'");
        }
        return Result.allow();
    }
}
