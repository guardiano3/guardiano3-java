package com.guardiano3.sdk;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Manages the two WebSocket channels for real-time call analysis: binary audio
 * uplink and typed JSON decision downlink.
 *
 * <pre>{@code
 * LiveSession live = new LiveSession.Builder()
 *     .baseUrl("https://api.guardiano3.com")
 *     .credential("go3_live_...")
 *     .sessionId(sessionId)
 *     .listener(new LiveSession.Listener() {
 *         public void onDecision(JsonObject frame) {
 *             System.out.println(frame.get("riskLevel") + " " + frame.get("riskScore"));
 *         }
 *     })
 *     .build();
 *
 * live.connect();
 * live.sendAudio(pcmBytes);
 * live.close();
 * }</pre>
 */
public final class LiveSession {

    private final OkHttpClient httpClient;
    private final String audioUrl;
    private final String streamUrl;
    private final String credential;
    private final Listener listener;
    private final Gson gson = new Gson();

    private WebSocket audioWs;
    private WebSocket streamWs;
    private volatile boolean closed;

    private LiveSession(Builder builder) {
        this.httpClient = builder.httpClient != null ? builder.httpClient : new OkHttpClient();
        String wsBase = builder.baseUrl.replaceFirst("^http(s?):", "ws$1:");
        if (wsBase.endsWith("/")) wsBase = wsBase.substring(0, wsBase.length() - 1);
        this.audioUrl = wsBase + "/v1/sessions/" + builder.sessionId + "/audio";
        this.streamUrl = wsBase + "/v1/sessions/" + builder.sessionId + "/stream";
        this.credential = builder.credential;
        this.listener = builder.listener != null ? builder.listener : new Listener() {};
    }

    /** Opens both WebSocket connections and sends the auth frame. */
    public void connect() {
        if (closed) throw new IllegalStateException("LiveSession is closed.");

        String authMsg = "{\"token\":\"" + credential + "\"}";

        Request audioReq = new Request.Builder().url(audioUrl).build();
        audioWs = httpClient.newWebSocket(audioReq, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                ws.send(authMsg);
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                if (!closed) listener.onDisconnect(code, reason);
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (!closed) listener.onDisconnect(1006, t.getMessage());
            }
        });

        Request streamReq = new Request.Builder().url(streamUrl).build();
        streamWs = httpClient.newWebSocket(streamReq, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                ws.send(authMsg);
            }
            @Override public void onMessage(WebSocket ws, String text) {
                dispatch(text);
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                if (!closed) listener.onDisconnect(code, reason);
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (!closed) listener.onDisconnect(1006, t.getMessage());
            }
        });
    }

    /** Sends raw PCM audio bytes (16-bit LE, 16kHz mono). */
    public void sendAudio(byte[] pcm) {
        if (audioWs == null) throw new IllegalStateException("Not connected.");
        audioWs.send(ByteString.of(pcm));
    }

    /** Closes both channels. */
    public void close() {
        closed = true;
        if (audioWs != null) { audioWs.close(1000, null); audioWs = null; }
        if (streamWs != null) { streamWs.close(1000, null); streamWs = null; }
    }

    private void dispatch(String raw) {
        JsonObject data;
        try {
            data = gson.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return;
        }
        String type = data.has("type") ? data.get("type").getAsString() : "";
        switch (type) {
            case "ready":
                listener.onReady(data);
                break;
            case "decision":
                listener.onDecision(data);
                break;
            case "decisionUpdate":
                listener.onDecisionUpdate(data);
                break;
            case "lagging":
                listener.onLagging(data);
                break;
            case "degraded":
                listener.onDegraded(data);
                break;
            case "error":
                listener.onError(data);
                break;
            case "ping":
                break;
            default:
                break;
        }
    }

    /**
     * Callbacks for decision stream frames. Override only the ones you need.
     */
    public static class Listener {
        public void onReady(JsonObject frame) {}
        public void onDecision(JsonObject frame) {}
        public void onDecisionUpdate(JsonObject frame) {}
        public void onLagging(JsonObject frame) {}
        /**
         * A stretch of the call was not transcribed, so no decision covers it.
         * The session goes on; do not read a quiet stream as a safe call.
         */
        public void onDegraded(JsonObject frame) {}
        public void onError(JsonObject frame) {}
        public void onDisconnect(int code, String reason) {}
    }

    public static final class Builder {
        private String baseUrl;
        private String credential;
        private String sessionId;
        private Listener listener;
        private OkHttpClient httpClient;

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder credential(String credential) { this.credential = credential; return this; }
        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder listener(Listener listener) { this.listener = listener; return this; }
        public Builder httpClient(OkHttpClient httpClient) { this.httpClient = httpClient; return this; }

        public LiveSession build() {
            if (baseUrl == null || baseUrl.isEmpty()) throw new IllegalArgumentException("baseUrl required");
            if (credential == null || credential.isEmpty()) throw new IllegalArgumentException("credential required");
            if (sessionId == null || sessionId.isEmpty()) throw new IllegalArgumentException("sessionId required");
            return new LiveSession(this);
        }
    }
}
