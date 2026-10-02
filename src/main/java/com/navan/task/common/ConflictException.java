package com.navan.task.common;

import java.util.Map;

/**
 * Thrown on a business conflict. Maps to HTTP 409.
 * Carries an optional structured payload (e.g. the reconcile mismatch detail).
 */
public class ConflictException extends RuntimeException {

    private final transient Map<String, Object> payload;

    public ConflictException(String message) {
        this(message, Map.of());
    }

    public ConflictException(String message, Map<String, Object> payload) {
        super(message);
        this.payload = payload;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
