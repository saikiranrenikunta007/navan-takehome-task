package com.navan.task.receipt;

import java.time.Instant;

/** API view of a stored receipt. */
public record ReceiptResponse(
        Long id,
        String originalName,
        Instant uploadedAt,
        boolean processed,
        Long transactionId
) {
}
