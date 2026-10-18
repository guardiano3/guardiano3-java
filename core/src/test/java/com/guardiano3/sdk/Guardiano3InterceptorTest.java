package com.guardiano3.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Driven against a real HTTP server rather than a mocked Chain.
 *
 * <p>The properties worth asserting here — that a retry reuses the idempotency
 * key, and that the body survives classification — are exactly the ones a
 * hand-rolled test double is most likely to get wrong in the SDK's favour. The
 * Python SDK shipped a bug for that reason: its stub populated a field the real
 * class left empty, so every API error lost its code and the unit tests passed.
 */
class Guardiano3InterceptorTest {

    private MockWebServer server;
    private OkHttpClient client;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient.Builder()
                .addInterceptor(new Guardiano3Interceptor(
                        "go3_live_test", new RetryPolicy(), millis -> { /* no waiting */ }))
                .build();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private Request post(String body) {
        return new Request.Builder()
                .url(server.url("/v1/sessions"))
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build();
    }

    @Test
    @DisplayName("the idempotency key is stable across a retry")
    void idempotencyKeyIsStableAcrossRetries() throws Exception {
        // The assertion that matters. A key generated inside the retry loop
        // would make each attempt a fresh write — the exact failure that
        // idempotency keys exist to prevent.
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"id\":\"sess_1\"}"));

        try (Response response = client.newCall(post("{\"a\":1}")).execute()) {
            assertEquals(201, response.code());
        }

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        String key = first.getHeader("Idempotency-Key");
        assertNotNull(key, "no idempotency key on an unsafe request");
        assertEquals(key, second.getHeader("Idempotency-Key"),
                "the key changed between attempts, so the retry was a second write");
    }

    @Test
    @DisplayName("the request body is replayed on a retry")
    void bodyIsReplayedOnRetry() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        try (Response ignored = client.newCall(post("{\"text\":\"hi\"}")).execute()) {
            // consumed below
        }
        assertEquals("{\"text\":\"hi\"}", server.takeRequest().getBody().readUtf8());
        assertEquals("{\"text\":\"hi\"}", server.takeRequest().getBody().readUtf8(),
                "the retry sent a different body from the first attempt");
    }

    @Test
    @DisplayName("a quota failure is not retried")
    void quotaIsNotRetried() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":{\"code\":\"QUOTA_EXCEEDED\",\"message\":\"spent\"}}"));

        try (Response response = client.newCall(post("{}")).execute()) {
            assertEquals(429, response.code());
        }
        assertEquals(1, server.getRequestCount(),
                "retrying a quota burns the caller's budget on requests that cannot succeed");
    }

    @Test
    @DisplayName("the response body is still readable after classification")
    void bodySurvivesClassification() throws Exception {
        // The interceptor reads the body to classify the failure. If it
        // consumed it, the generated client would see an empty payload — which
        // is precisely the bug the Python SDK's dogfood tests found.
        server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"no such session\"}}"));

        try (Response response = client.newCall(post("{}")).execute()) {
            assertEquals(404, response.code());
            assertTrue(response.body().string().contains("NOT_FOUND"),
                    "classification consumed the body");
        }
    }

    @Test
    @DisplayName("the credential is attached, and a caller's own header wins")
    void credentialHandling() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        try (Response ignored = client.newCall(post("{}")).execute()) { /* consumed below */ }
        assertEquals("Bearer go3_live_test", server.takeRequest().getHeader("Authorization"));

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        Request explicit = post("{}").newBuilder()
                .header("Authorization", "Bearer caller-supplied")
                .header("Idempotency-Key", "mine")
                .build();
        try (Response ignored = client.newCall(explicit).execute()) { /* consumed below */ }
        RecordedRequest recorded = server.takeRequest();
        assertEquals("Bearer caller-supplied", recorded.getHeader("Authorization"));
        assertEquals("mine", recorded.getHeader("Idempotency-Key"),
                "the caller's key was overwritten");
    }

    @Test
    @DisplayName("a safe method gets no idempotency key")
    void safeMethodsAreNotKeyed() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        Request get = new Request.Builder().url(server.url("/v1/whoami")).get().build();
        try (Response ignored = client.newCall(get).execute()) { /* consumed below */ }
        assertNull(server.takeRequest().getHeader("Idempotency-Key"),
                "a GET does not need replay protection and should not claim it");
    }
}
