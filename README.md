# GuardianO3 Java SDK

> **Not published yet.** Version 0.1.0 is being prepared. The Maven coordinates
> below work once it is on Maven Central.

The official Java client for the [GuardianO3](https://guardiano3.com) API:
real-time fraud intelligence for phone calls. Send a transcript, an audio clip
or a live call, and get a risk decision back.

You need an API key from the developer console at
[guardiano3.com](https://guardiano3.com).

## Install

Java 11+. Maven:

```xml
<dependency>
  <groupId>com.guardiano3</groupId>
  <artifactId>guardiano3-sdk-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.guardiano3:guardiano3-sdk-core:0.1.0")
```

`guardiano3-sdk-core` brings in the generated client, `guardiano3-sdk`, as a
dependency.

## How this repository is laid out

| Module | What it is |
|---|---|
| `core/` (`guardiano3-sdk-core`) | **Hand-written**: the interceptor (auth, idempotency, retries), typed errors, pagination, live sessions |
| `generated/` (`guardiano3-sdk`) | **Generated** from `openapi/v1.json` by `scripts/generate.sh`. Never edited by hand |
| `openapi/v1.json` | A copy of the GuardianO3 API contract, updated whenever the API changes |

`core` depends on `generated` as an ordinary artifact, so regenerating never
touches hand-written code. Build and test both with `mvn verify`.

Pull requests are welcome for the hand-written part; see
[CONTRIBUTING](https://github.com/guardiano3/.github/blob/main/CONTRIBUTING.md).
If generated code is wrong, open an issue: the fix belongs in the contract.

## A client

<!-- sample:java-client -->
```java
import com.guardiano3.sdk.Guardiano3Interceptor;
import okhttp3.OkHttpClient;
import com.guardiano3.generated.ApiClient;

ApiClient apiClient = new ApiClient();  // https://api.guardiano3.com by default;
                                        // setBasePath() reaches another environment

// The interceptor is what turns the generated client into an SDK: it
// authenticates, adds an idempotency key to unsafe requests, and retries what
// is safe to retry. One interceptor rather than 36 wrapped operations.
OkHttpClient http = apiClient.getHttpClient().newBuilder()
        .addInterceptor(new Guardiano3Interceptor("go3_live_..."))
        .build();
apiClient.setHttpClient(http);
```

An API key and a user access token are both sent as `Authorization: Bearer`;
the API tells them apart by the `go3_` prefix, so there is nothing to configure
differently. A credential the caller sets by hand is never overwritten.

## Errors

<!-- sample:java-errors -->
```java
import com.guardiano3.sdk.*;
import com.guardiano3.generated.ApiException;
import com.guardiano3.generated.api.V1SessionsApi;

V1SessionsApi sessions = new V1SessionsApi(apiClient);
try {
    sessions.getSessionV1SessionsSessionIdGet("nope");
} catch (ApiException e) {
    Guardiano3Exception error = Errors.from(
            e.getCode(), e.getResponseBody(),
            e.getResponseHeaders() == null ? null
                    : e.getResponseHeaders().getOrDefault("x-request-id", java.util.List.of())
                            .stream().findFirst().orElse(null),
            null);
    if (error instanceof QuotaExceededException) {
        throw error;                       // do not retry: waiting will not help
    } else if (error instanceof RateLimitException rate) {
        System.out.println("retry after " + rate.getRetryAfterSeconds());
    } else if (error instanceof NotFoundException) {
        // Also thrown when the row exists but belongs to someone else.
        System.out.println(error.getCode() + " " + error.getRequestId());
    } else if (error instanceof ValidationException) {
        error.getDetails().forEach(System.out::println);
    }
}
```

| Class | Status |
|---|---|
| `AuthenticationException` | 401 |
| `PermissionException` | 403 |
| `NotFoundException` | 404 |
| `ConflictException` | 409 |
| `ValidationException` | 422 |
| `RateLimitException` | 429 |
| `QuotaExceededException` | 429 with `QUOTA_EXCEEDED` — **extends `RateLimitException`**, so order your `instanceof` checks narrowest first |
| `ServerException` | 5xx |

All extend `Guardiano3Exception` and carry `getCode()`, `getStatusCode()`,
`getRequestId()`, `getDetails()` and `getRetryAfterSeconds()`.

**An unrecognised code falls back to the class for its HTTP status.** New codes
are added without a version bump, so a client that threw something unhandleable
on one would make adding a code a breaking change.

`getRequestId()` is also baked into `getMessage()` — a field nobody prints is a
field nobody pastes into a support ticket.

## Retries

| | |
|---|---|
| Retried | 429, 502, 503, 504 |
| **Not** retried | 4xx other than 429, **500**, and any `QUOTA_EXCEEDED` |
| Attempts | 3 |
| Backoff | 0.5s, doubling, capped at 20s, ±25% jitter |
| `Retry-After` | Honoured, and capped — a hostile header must not park the client |

500 is excluded deliberately: the API returns it for an unexpected fault, not a
transient one, and hammering it turns one incident into a bigger one.

<!-- sample:java-retry-policy -->
```java
import com.guardiano3.sdk.Guardiano3Interceptor;
import com.guardiano3.sdk.RetryPolicy;

new Guardiano3Interceptor(
        "go3_live_...",
        new RetryPolicy(5, 1_000L, 30_000L, 2.0, 0.25, Math::random),
        Thread::sleep);
```

`RetryPolicy.withMaxAttempts(1)` disables retrying.

## Idempotency

Every unsafe request carries an `Idempotency-Key`, generated **once per logical
call, outside the retry loop**. Generating it per attempt would make each retry
a fresh write — the exact failure the mechanism exists to prevent. Set the
header yourself to control it; the interceptor never overwrites one you supply,
and safe methods get none.

## Pagination

<!-- sample:java-pagination -->
```java
import com.guardiano3.sdk.Pagination;

for (var entry : Pagination.<Object>iterate(cursor -> {
    var page = sessions.listHistoryV1HistoryGet(cursor, 50);
    return new Pagination.Page<>(page.getItems(), page.getNextCursor());
})) {
    System.out.println(entry);
}
```

Lazy: a page is fetched only when the previous one has been consumed, so taking
the first item costs one request rather than walking the whole history. `break`
out and no further request is made. Cursors never reach you — they are opaque,
and a client that builds its own breaks the moment the sort key changes.

`Pagination.collect(fetch, max)` when you want a bounded list instead.

## WebSocket / Live Sessions

`LiveSession` manages the two WebSocket channels for real-time call
analysis: binary audio uplink and typed JSON decision downlink. It uses
OkHttp's built-in WebSocket support, handles authentication, frame parsing,
and connection lifecycle.

<!-- sample:java-live -->
```java
import com.guardiano3.sdk.LiveSession;
import com.google.gson.JsonObject;

// 1. Create a session via REST (using the generated API)
// String sessionId = sessions.createSessionV1SessionsPost(...).getId();

// 2. Open the live channels
LiveSession live = new LiveSession.Builder()
    .baseUrl("https://api.guardiano3.com")
    .credential("go3_live_...")
    .sessionId(sessionId)
    .listener(new LiveSession.Listener() {
        @Override public void onReady(JsonObject frame) {
            System.out.println("Ready: " + frame.get("sessionId"));
        }
        @Override public void onDecision(JsonObject frame) {
            System.out.println(frame.get("riskLevel") + " " + frame.get("riskScore"));
        }
        @Override public void onDecisionUpdate(JsonObject frame) {
            System.out.println("Updated: " + frame.get("riskLevel"));
        }
        @Override public void onLagging(JsonObject frame) {
            System.err.println("Dropped chunks: " + frame.get("droppedChunks"));
        }
        @Override public void onError(JsonObject frame) {
            System.err.println("Error: " + frame.getAsJsonObject("error"));
        }
        @Override public void onDisconnect(int code, String reason) {
            System.out.println("Disconnected: " + code + " " + reason);
        }
    })
    .build();

live.connect();

// 3. Send PCM audio (16-bit LE, 16kHz mono)
live.sendAudio(pcmBytes);

// 4. When done
live.close();
```

| Callback | Frame type | When |
|---|---|---|
| `onReady` | `ready` | Session authenticated, audio accepted |
| `onDecision` | `decision` | Scored chunk; `pending` verification means an update follows |
| `onDecisionUpdate` | `decisionUpdate` | Final decision for a chunk first sent as `pending`; replace it in place |
| `onLagging` | `lagging` | Audio arriving faster than transcription can process |
| `onDegraded` | `degraded` | Part of the call was not transcribed; no decision covers it. Not fatal: do not read a quiet stream as a safe call |
| `onError` | `error` | Fatal; server closes after sending |
| `onDisconnect` | — | Either WebSocket closed or failed |

Both channels authenticate by sending `{"token": "..."}` as the first text
frame. Audio is sent as raw binary (PCM 16-bit LE, 16kHz mono). The
generated `DecisionFrame`, `ReadyFrame`, etc. can be used to deserialize
frames from the raw `JsonObject`.

## Testing against it

The interceptor is plain OkHttp, so `MockWebServer` is the natural double —
which is how the SDK's own core tests run, with no network and no mocking
framework.

## What it will never do

**No business logic, no authorization decisions.** Transport, serialisation,
authentication, models, errors, pagination and retries — nothing else. Anything
the SDK decided, an attacker could decide differently with `curl`, so the rules
live on the server.

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

The licence covers this SDK's source code. Using the GuardianO3 API requires an
API key and acceptance of the [Terms of Service](https://guardiano3.com/terms).

Security issues: see [SECURITY](https://github.com/guardiano3/.github/blob/main/SECURITY.md).
Never put API keys, call audio or transcripts in an issue.
