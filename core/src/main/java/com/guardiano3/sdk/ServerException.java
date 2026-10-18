package com.guardiano3.sdk;

import java.util.List;

/** Server failure. See {@link Guardiano3Exception} for the shared fields. */
public class ServerException extends Guardiano3Exception {
    private static final long serialVersionUID = 1L;

    public ServerException(String message, int statusCode, String code, String requestId,
            List<ErrorDetail> details, Integer retryAfterSeconds) {
        super(message, statusCode, code, requestId, details, retryAfterSeconds);
    }
}
