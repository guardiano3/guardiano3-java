package com.guardiano3.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    @DisplayName("a quota is never retried; a rate limit always is")
    void quotaIsTerminal() {
        // Waiting 400ms does not clear a daily allowance, and retrying burns
        // the caller's remaining budget on requests that cannot succeed.
        RetryPolicy policy = new RetryPolicy();
        assertFalse(policy.shouldRetry(1, 429, "QUOTA_EXCEEDED"));
        assertTrue(policy.shouldRetry(1, 429, "RATE_LIMITED"));
    }

    @Test
    @DisplayName("500 is not retried; 502/503/504 are")
    void fiveHundredIsTerminal() {
        // The API returns 500 for an unexpected fault, not a transient one.
        // Hammering it turns one incident into a bigger one.
        RetryPolicy policy = new RetryPolicy();
        assertFalse(policy.shouldRetry(1, 500, "INTERNAL_ERROR"));
        for (int status : new int[] {502, 503, 504}) {
            assertTrue(policy.shouldRetry(1, status, null), "should retry " + status);
        }
    }

    @Test
    @DisplayName("attempts are bounded")
    void attemptsAreBounded() {
        RetryPolicy policy = new RetryPolicy();
        assertTrue(policy.shouldRetry(2, 503, null));
        assertFalse(policy.shouldRetry(3, 503, null), "the third attempt is the last");
        assertFalse(RetryPolicy.withMaxAttempts(1).shouldRetry(1, 503, null),
                "maxAttempts=1 disables retrying");
    }

    @Test
    @DisplayName("Retry-After beats the guess, but never past the ceiling")
    void retryAfterWins() {
        RetryPolicy policy = new RetryPolicy();
        assertEquals(2000L, policy.backoffMillis(3, 2));
        // A hostile or mistaken header must not park the client for an hour.
        assertEquals(20_000L, policy.backoffMillis(1, 3600));
    }

    @Test
    @DisplayName("backoff is jittered in both directions and stays under the ceiling")
    void backoffIsJittered() {
        // Without jitter, every client refused at the same instant retries at
        // the same instant and re-creates the overload the limit prevented.
        RetryPolicy high = new RetryPolicy(3, 500L, 20_000L, 2.0, 0.25, () -> 1.0);
        RetryPolicy low = new RetryPolicy(3, 500L, 20_000L, 2.0, 0.25, () -> 0.0);
        long highDelay = high.backoffMillis(2, null);
        long lowDelay = low.backoffMillis(2, null);
        assertTrue(lowDelay < highDelay, "jitter produced no spread");
        assertTrue(highDelay <= 20_000L, "jitter pushed past the ceiling");
    }
}
