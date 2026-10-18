package com.guardiano3.sdk;

import java.io.IOException;
import java.util.UUID;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Authentication, idempotency and retries, as one OkHttp interceptor.
 *
 * <p>An interceptor rather than a wrapper around each operation: the generated
 * client has 36 of them, and 36 call sites is 36 chances to forget one. This
 * sits under all of them.
 *
 * <p>Install it on the generated {@code ApiClient}:
 * <pre>{@code
 * ApiClient client = new ApiClient();  // https://api.guardiano3.com by default
 * client.getHttpClient().newBuilder()
 *     .addInterceptor(new Guardiano3Interceptor("go3_live_..."))
 *     .build();
 * }</pre>
 */
public final class Guardiano3Interceptor implements Interceptor {

    private final String credential;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;

    /** Injectable so tests assert the retry logic without waiting for it. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public Guardiano3Interceptor(String credential) {
        this(credential, new RetryPolicy(), Thread::sleep);
    }

    public Guardiano3Interceptor(String credential, RetryPolicy retryPolicy, Sleeper sleeper) {
        this.credential = credential;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
    }

    private static boolean isUnsafe(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        Request.Builder builder = original.newBuilder();

        if (credential != null && !credential.isEmpty()
                && original.header("Authorization") == null) {
            builder.header("Authorization", "Bearer " + credential);
        }
        // Generated **once**, here, outside the retry loop. Inside it, every
        // retry would be a fresh write — the exact failure idempotency keys
        // exist to prevent. A key the caller set is never overwritten.
        if (isUnsafe(original.method()) && original.header("Idempotency-Key") == null) {
            builder.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        Request request = builder.build();

        Response response = null;
        for (int attempt = 1; ; attempt++) {
            if (response != null) {
                response.close();
            }
            response = chain.proceed(request);
            if (response.code() < 400) {
                return response;
            }

            // Peek rather than consume: the body must still be readable by the
            // generated client afterwards. `peekBody` is what makes that safe.
            String body = "";
            try {
                body = response.peekBody(64 * 1024).string();
            } catch (IOException ignored) {
                // Classification is best-effort; the status still decides.
            }
            Guardiano3Exception error = Errors.from(
                    response.code(), body,
                    response.header("X-Request-Id"), response.header("Retry-After"));

            if (!retryPolicy.shouldRetry(attempt, response.code(), error.getCode())) {
                return response;
            }
            try {
                sleeper.sleep(retryPolicy.backoffMillis(attempt + 1, error.getRetryAfterSeconds()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return response;
            }
        }
    }
}
