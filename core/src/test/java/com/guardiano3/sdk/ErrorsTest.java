package com.guardiano3.sdk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorsTest {

    private static String envelope(String code, String message) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"requestId\":\"req_0123456789abcdef0123456789abcdef\"}}";
    }

    @Test
    @DisplayName("the code decides the type, not the status")
    void codeDecidesTheType() {
        // The whole point of the hierarchy: a caller branches on what went
        // wrong, not on an integer. 429 is two different situations.
        assertInstanceOf(QuotaExceededException.class,
                Errors.from(429, envelope("QUOTA_EXCEEDED", "spent"), null, null));
        assertInstanceOf(RateLimitException.class,
                Errors.from(429, envelope("RATE_LIMITED", "slow down"), null, null));
    }

    @Test
    @DisplayName("a quota is still a rate limit for a caller who catches broadly")
    void quotaIsARateLimit() {
        Guardiano3Exception error = Errors.from(429, envelope("QUOTA_EXCEEDED", "spent"), null, null);
        assertInstanceOf(RateLimitException.class, error,
                "QuotaExceededException must extend RateLimitException");
    }

    @Test
    @DisplayName("an unknown code falls back to the status, and still reaches the caller")
    void unknownCodeFallsBack() {
        // New codes are added without a version bump. A client that broke on
        // one would make adding a code a breaking change.
        Guardiano3Exception error =
                Errors.from(404, envelope("SOME_FUTURE_CODE", "nope"), null, null);
        assertInstanceOf(NotFoundException.class, error);
        assertEquals("SOME_FUTURE_CODE", error.getCode());
    }

    @Test
    @DisplayName("the request id is in the message, not only in a field")
    void requestIdIsInTheMessage() {
        // A field nobody prints is a field nobody pastes into a support ticket.
        Guardiano3Exception error = Errors.from(404, envelope("NOT_FOUND", "no such session"), null, null);
        assertTrue(error.getMessage().contains("req_0123456789abcdef0123456789abcdef"),
                error.getMessage());
        assertTrue(error.getMessage().contains("NOT_FOUND"), error.getMessage());
    }

    @Test
    @DisplayName("the header supplies the request id when the body cannot")
    void headerSuppliesRequestId() {
        // An error raised before the handler ran has no envelope but still
        // carries the correlation id.
        Guardiano3Exception error = Errors.from(502, "upstream exploded", "req_fromheader", null);
        assertEquals("req_fromheader", error.getRequestId());
        assertInstanceOf(ServerException.class, error);
    }

    @Test
    @DisplayName("a body that is not the envelope does not break error reporting")
    void malformedBodyIsSurvivable() {
        // Failing while reporting a failure is the worst possible time.
        Guardiano3Exception error = Errors.from(500, "<html>gateway</html>", null, null);
        assertInstanceOf(ServerException.class, error);
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    @DisplayName("validation details reach the caller")
    void detailsAreParsed() {
        String body = "{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"bad\","
                + "\"details\":[{\"field\":\"email\",\"issue\":\"must be valid\"}]}}";
        Guardiano3Exception error = Errors.from(422, body, null, null);
        assertInstanceOf(ValidationException.class, error);
        assertEquals(1, error.getDetails().size());
        assertEquals("email", error.getDetails().get(0).getField());
    }

    @Test
    @DisplayName("a malformed Retry-After is no worse than an absent one")
    void malformedRetryAfterIsIgnored() {
        assertNull(Errors.from(429, envelope("RATE_LIMITED", "x"), null, "soon").getRetryAfterSeconds());
        assertEquals(30,
                Errors.from(429, envelope("RATE_LIMITED", "x"), null, "30").getRetryAfterSeconds());
    }
}
