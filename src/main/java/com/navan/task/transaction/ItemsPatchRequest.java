package com.navan.task.transaction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** User override of the full line-item set (edit / merge / split). */
public record ItemsPatchRequest(
        @NotEmpty List<@Valid ItemInput> items
) {
    public record ItemInput(
            @NotNull String description,
            @NotNull BigDecimal amount,
            Integer quantity,
            BigDecimal taxAmount
    ) {
    }
}
