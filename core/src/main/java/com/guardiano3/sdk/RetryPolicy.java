package com.guardiano3.sdk;

import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * What is safe to repeat. Same rules as the Python, TypeScript and Go cores.
 *
 * <ul>
 *   <li>Only 429 and 502/503/504 are retried. A 400 or a 404 will fail again;
 *       retrying wastes the caller's time and the API's capacity. <b>A 500 is
 *       not retried either</b> — the API returns it for an unexpected fault,
 *       not a transient one, and hammering it makes an incident worse.
 *   <li><b>{@code Retry-After} is honoured.</b> The server knows when its
 *       window resets; guessing produces a wasted wait or a second refusal.
 *   <li><b>Backoff is jittered.</b> Without it, every client refused at the
 *       same instant retries at the same instant and re-creates the overload
 *       the limit existed to prevent.
 * </ul>
 */
public final class RetryPolicy {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

    /** A daily allowance does not clear by waiting, so never spin on it. */
    private static final Set<String> NON_RETRYABLE_CODES = Set.of("QUOTA_EXCEEDED");

    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final double multiplier;
    private final double jitter;
    private final DoubleSupplier random;

    public RetryPolicy() {
        this(3, 500L, 20_000L, 2.0, 0.25, Math::random);
    }

    public RetryPolicy(int maxAttempts, long initialBackoffMillis, long maxBackoffMillis,
            double multiplier, double jitter, DoubleSupplier random) {
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.multiplier = multiplier;
        this.jitter = jitter;
        this.random = random;
    }

    /** Attempts, including the first. 1 disables retrying. */
    public static RetryPolicy withMaxAttempts(int maxAttempts) {
        return new RetryPolicy(maxAttempts, 500L, 20_000L, 2.0, 0.25, Math::random);
    }

    public int getMaxAttempts() { return maxAttempts; }

    /** Whether attempt {@code attempt} (1-based) may be repeated. */
    public boolean shouldRetry(int attempt, int status, String code) {
        if (attempt >= maxAttempts) {
            return false;
        }
        if (code != null && NON_RETRYABLE_CODES.contains(code)) {
            return false;
        }
        return RETRYABLE_STATUSES.contains(status);
    }

    /** Milliseconds to wait before the given attempt. */
    public long backoffMillis(int attempt, Integer retryAfterSeconds) {
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            // The server's answer beats our guess -- but never past the
            // ceiling, or a hostile header parks the client indefinitely.
            return Math.min(retryAfterSeconds * 1000L, maxBackoffMillis);
        }
        double base = initialBackoffMillis;
        for (int i = 1; i < attempt; i++) {
            base *= multiplier;
        }
        base = Math.min(base, maxBackoffMillis);
        double spread = base * jitter;
        double delay = base + (random.getAsDouble() * 2 - 1) * spread;
        return (long) Math.max(0, delay);
    }
}
