package com.guardiano3.sdk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Turns an error response into the right {@link Guardiano3Exception} subclass. */
public final class Errors {

    private Errors() {}

    /**
     * The same table as every other GuardianO3 SDK (Python, TypeScript, Go).
     *
     * <p>Kept as an explicit map rather than derived from the status, because
     * the same status carries different meanings — a 429 is retryable when it
     * is RATE_LIMITED and terminal when it is QUOTA_EXCEEDED, and a caller has
     * to be able to tell.
     */
    private static final Map<String, Kind> BY_CODE = Map.ofEntries(
            Map.entry("UNAUTHENTICATED", Kind.AUTHENTICATION),
            Map.entry("INVALID_CREDENTIALS", Kind.AUTHENTICATION),
            Map.entry("INVALID_TOKEN", Kind.AUTHENTICATION),
            Map.entry("TOKEN_REVOKED", Kind.AUTHENTICATION),
            Map.entry("INVALID_REFRESH_TOKEN", Kind.AUTHENTICATION),
            Map.entry("REFRESH_TOKEN_REUSED", Kind.AUTHENTICATION),
            Map.entry("INVALID_API_KEY", Kind.AUTHENTICATION),
            Map.entry("REAUTHENTICATION_REQUIRED", Kind.AUTHENTICATION),
            Map.entry("MFA_REQUIRED", Kind.AUTHENTICATION),
            Map.entry("FORBIDDEN", Kind.PERMISSION),
            Map.entry("INSUFFICIENT_SCOPE", Kind.PERMISSION),
            Map.entry("EMAIL_NOT_VERIFIED", Kind.PERMISSION),
            Map.entry("ACCOUNT_SUSPENDED", Kind.PERMISSION),
            Map.entry("WRONG_ENVIRONMENT", Kind.PERMISSION),
            Map.entry("NOT_FOUND", Kind.NOT_FOUND),
            Map.entry("ALREADY_EXISTS", Kind.CONFLICT),
            Map.entry("CONFLICT", Kind.CONFLICT),
            Map.entry("IDEMPOTENCY_KEY_REUSED", Kind.CONFLICT),
            Map.entry("VALIDATION_ERROR", Kind.VALIDATION),
            Map.entry("INVALID_REQUEST", Kind.VALIDATION),
            Map.entry("PASSWORD_POLICY_VIOLATION", Kind.VALIDATION),
            Map.entry("BREACHED_PASSWORD", Kind.VALIDATION),
            Map.entry("UNSUPPORTED_MEDIA_TYPE", Kind.VALIDATION),
            Map.entry("PAYLOAD_TOO_LARGE", Kind.VALIDATION),
            Map.entry("RATE_LIMITED", Kind.RATE_LIMIT),
            Map.entry("CONCURRENCY_LIMIT", Kind.RATE_LIMIT),
            Map.entry("QUOTA_EXCEEDED", Kind.QUOTA),
            Map.entry("INTERNAL_ERROR", Kind.SERVER),
            Map.entry("SERVICE_UNAVAILABLE", Kind.SERVER),
            Map.entry("UPSTREAM_ERROR", Kind.SERVER),
            Map.entry("UPSTREAM_TIMEOUT", Kind.SERVER),
            Map.entry("NOT_CONFIGURED", Kind.SERVER));

    private enum Kind {
        AUTHENTICATION, PERMISSION, NOT_FOUND, CONFLICT, VALIDATION,
        RATE_LIMIT, QUOTA, SERVER, UNKNOWN
    }

    private static Kind kindForStatus(int status) {
        switch (status) {
            case 400: case 413: case 415: case 422: return Kind.VALIDATION;
            case 401: return Kind.AUTHENTICATION;
            case 403: return Kind.PERMISSION;
            case 404: return Kind.NOT_FOUND;
            case 409: return Kind.CONFLICT;
            case 429: return Kind.RATE_LIMIT;
            default: return status >= 500 ? Kind.SERVER : Kind.UNKNOWN;
        }
    }

    /**
     * Build the exception for a failed response.
     *
     * <p>An unrecognised code falls back to the kind for its HTTP status rather
     * than throwing something unhandleable. New codes are added without a
     * version bump, so a client that broke on one would make adding a code a
     * breaking change.
     */
    public static Guardiano3Exception from(
            int status, String body, String requestIdHeader, String retryAfterHeader) {
        String code = null;
        String message = null;
        String requestId = requestIdHeader;
        List<Guardiano3Exception.ErrorDetail> details = new ArrayList<>();

        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject error = parsed.getAsJsonObject().getAsJsonObject("error");
                if (error != null) {
                    code = optString(error, "code");
                    message = optString(error, "message");
                    String envelopeId = optString(error, "requestId");
                    if (envelopeId != null) {
                        requestId = envelopeId;
                    }
                    if (error.has("details") && error.get("details").isJsonArray()) {
                        error.getAsJsonArray("details").forEach(item -> {
                            if (item.isJsonObject()) {
                                JsonObject detail = item.getAsJsonObject();
                                details.add(new Guardiano3Exception.ErrorDetail(
                                        optString(detail, "field"), optString(detail, "issue")));
                            }
                        });
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A body that is not the envelope is not a reason to fail while
            // reporting a failure. Status and headers still carry enough.
        }

        if (message == null || message.isEmpty()) {
            message = "request failed with status " + status;
        }

        Integer retryAfter = null;
        if (retryAfterHeader != null) {
            try {
                retryAfter = Integer.valueOf(retryAfterHeader.trim());
            } catch (NumberFormatException ignored) {
                // A malformed Retry-After is no worse than an absent one.
            }
        }

        Kind kind = code != null ? BY_CODE.get(code) : null;
        if (kind == null) {
            kind = kindForStatus(status);
        }

        switch (kind) {
            case AUTHENTICATION:
                return new AuthenticationException(message, status, code, requestId, details, retryAfter);
            case PERMISSION:
                return new PermissionException(message, status, code, requestId, details, retryAfter);
            case NOT_FOUND:
                return new NotFoundException(message, status, code, requestId, details, retryAfter);
            case CONFLICT:
                return new ConflictException(message, status, code, requestId, details, retryAfter);
            case VALIDATION:
                return new ValidationException(message, status, code, requestId, details, retryAfter);
            case QUOTA:
                return new QuotaExceededException(message, status, code, requestId, details, retryAfter);
            case RATE_LIMIT:
                return new RateLimitException(message, status, code, requestId, details, retryAfter);
            case SERVER:
                return new ServerException(message, status, code, requestId, details, retryAfter);
            default:
                return new Guardiano3Exception(message, status, code, requestId, details, retryAfter);
        }
    }

    private static String optString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
