package com.navan.task.transaction;

import com.navan.task.common.ItemizeStatus;
import com.navan.task.common.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reconciliation rule for the auto-itemize feature.
 *
 * Items in the fixtures are NET amounts, so a transaction reconciles when:
 *   sum(line_item.amount) + sum(tax.amount) == grand_total   (at scale 2)
 *
 * We never add a balancing line or alter grand_total to force a match.
 */
@Component
public class ReconciliationService {

    public BigDecimal itemsTotal(List<LineItem> items) {
        return items.stream()
                .map(LineItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal taxesTotal(List<Tax> taxes) {
        return taxes.stream()
                .map(Tax::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal computedTotal(List<LineItem> items, List<Tax> taxes) {
        return Money.normalize(itemsTotal(items).add(taxesTotal(taxes)));
    }

    /** True when items + taxes equal grand_total and at least one item exists. */
    public boolean reconciles(Transaction txn) {
        if (txn.getLineItems().isEmpty()) {
            return false;
        }
        BigDecimal computed = computedTotal(txn.getLineItems(), txn.getTaxes());
        return Money.equal(computed, txn.getGrandTotal());
    }

    /**
     * Status produced by auto-itemize (before user confirmation):
     * reconciled -> COMPLETE, otherwise NEEDS_REVIEW.
     * FAILED is reserved for extraction failures handled upstream.
     */
    public ItemizeStatus statusFor(Transaction txn) {
        return reconciles(txn) ? ItemizeStatus.COMPLETE : ItemizeStatus.NEEDS_REVIEW;
    }
}
