package com.navan.task.common;

/**
 * Thrown when OCR extraction cannot produce a required header field
 * (merchant, date, currency, total). Maps to HTTP 422.
 * We never invent "Unknown Merchant" / 1970-01-01 / 0.00.
 */
public class ExtractionException extends RuntimeException {
    public ExtractionException(String message) {
        super(message);
    }
}
