package com.ims.common.idempotency;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Control-flow signal: the request was already processed; render the stored response.
 * Rendered by {@code GlobalExceptionHandler}, so the replay does not need to match the
 * controller's declared return type.
 */
public class IdempotentReplay extends RuntimeException {

    private final int status;
    private final transient JsonNode body;

    public IdempotentReplay(int status, JsonNode body) {
        super("Idempotent replay", null, false, false);
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public JsonNode getBody() {
        return body;
    }
}
