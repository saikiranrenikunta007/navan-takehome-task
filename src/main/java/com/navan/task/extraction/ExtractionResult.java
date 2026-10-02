package com.navan.task.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Structured result of parsing stored OCR text. Immutable value object.
 * Header fields are mandatory; missing any of them is an extraction failure.
 */
public record ExtractionResult(
        String merchant,
        LocalDate date,
        String currency,
        BigDecimal grandTotal,
        List<TaxLine> taxes,
        List<ItemLine> lineItems
) {
    public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {
    }

    public record ItemLine(String description, BigDecimal amount) {
    }
}
