package com.guardiano3.sdk;

import java.util.List;

/**
 * The plan's allowance is spent.
 *
 * <p>Extends {@link RateLimitException} so a caller who catches broadly still
 * catches it — but the retry policy treats it as terminal, because waiting a
 * few seconds does not clear a daily quota and retrying burns the caller's
 * remaining budget on requests that cannot succeed.
 */
public class QuotaExceededException extends RateLimitException {
    private static final long serialVersionUID = 1L;

    public QuotaExceededException(String message, int statusCode, String code, String requestId,
            List<ErrorDetail> details, Integer retryAfterSeconds) {
        super(message, statusCode, code, requestId, details, retryAfterSeconds);
    }
}
