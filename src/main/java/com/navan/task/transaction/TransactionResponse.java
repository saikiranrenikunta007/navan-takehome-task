package com.navan.task.transaction;

import com.navan.task.common.ItemizeStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** API view of a transaction with its taxes and line items. */
public record TransactionResponse(
        Long id,
        Long receiptId,
        String merchant,
        LocalDate date,
        String currency,
        BigDecimal grandTotal,
        ItemizeStatus itemizeStatus,
        List<TaxView> taxes,
        List<LineItemView> lineItems
) {
    public record TaxView(Long id, String name, BigDecimal rate, BigDecimal amount) {
    }

    public record LineItemView(Long id, String description, BigDecimal amount, Integer quantity, BigDecimal taxAmount) {
    }

    public static TransactionResponse from(Transaction t) {
        List<TaxView> taxViews = t.getTaxes().stream()
                .map(x -> new TaxView(x.getId(), x.getName(), x.getRate(), x.getAmount()))
                .toList();
        List<LineItemView> itemViews = t.getLineItems().stream()
                .map(x -> new LineItemView(x.getId(), x.getDescription(), x.getAmount(), x.getQuantity(), x.getTaxAmount()))
                .toList();
        return new TransactionResponse(
                t.getId(), t.getReceiptId(), t.getMerchant(), t.getDate(), t.getCurrency(),
                t.getGrandTotal(), t.getItemizeStatus(), taxViews, itemViews);
    }
}
