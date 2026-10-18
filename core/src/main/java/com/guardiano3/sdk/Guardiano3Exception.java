package com.guardiano3.sdk;

import java.util.Collections;
import java.util.List;

/**
 * Every failure the API reports through its error envelope.
 *
 * <p>The generated client throws {@code ApiException} carrying a status code and
 * a raw body. That is enough to know something failed and not enough to decide
 * what to do, which is what a caller actually needs. This maps the envelope's
 * stable {@code error.code} onto a small hierarchy so a caller writes
 * {@code catch (RateLimitException e)} rather than comparing integers.
 *
 * <p>Mirrors the Python, TypeScript and Go cores exactly. Three SDKs that
 * classify the same failure differently produce three different incidents.
 */
public class Guardiano3Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String code;
    private final String requestId;
    private final List<ErrorDetail> details;
    private final Integer retryAfterSeconds;

    public Guardiano3Exception(
            String message,
            int statusCode,
            String code,
            String requestId,
            List<ErrorDetail> details,
            Integer retryAfterSeconds) {
        // The request id goes in the message, not only in a field. It is the
        // value support asks for, and a field nobody prints is a field nobody
        // pastes into the report.
        super(buildMessage(message, code, requestId));
        this.statusCode = statusCode;
        this.code = code;
        this.requestId = requestId;
        this.details = details == null ? Collections.emptyList() : List.copyOf(details);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static String buildMessage(String message, String code, String requestId) {
        StringBuilder out = new StringBuilder(message == null ? "request failed" : message);
        if (code != null && !code.isEmpty()) {
            out.append(" [").append(code).append(']');
        }
        if (requestId != null && !requestId.isEmpty()) {
            out.append(" (request ").append(requestId).append(')');
        }
        return out.toString();
    }

    public int getStatusCode() { return statusCode; }

    /** The stable machine-readable code. Branch on this, never on the message. */
    public String getCode() { return code; }

    /** Quote this in a bug report; it appears in every server log line for the request. */
    public String getRequestId() { return requestId; }

    /** Per-field validation failures. Empty unless the API sent any. */
    public List<ErrorDetail> getDetails() { return details; }

    /** Seconds the server asked the caller to wait, when it said. */
    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }

    /** One field-level validation failure. */
    public static final class ErrorDetail {
        private final String field;
        private final String issue;

        public ErrorDetail(String field, String issue) {
            this.field = field;
            this.issue = issue;
        }

        public String getField() { return field; }
        public String getIssue() { return issue; }

        @Override
        public String toString() { return field + ": " + issue; }
    }
}
