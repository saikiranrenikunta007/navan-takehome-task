package com.navan.task.transaction;

import com.navan.task.common.ConflictException;
import com.navan.task.common.ItemizeStatus;
import com.navan.task.common.Money;
import com.navan.task.common.NotFoundException;
import com.navan.task.extraction.ExtractionResult;
import com.navan.task.observability.ReceiptMetrics;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Owns the single transaction per receipt: atomic upsert from an extraction
 * result, re-itemize, user patch, and completion.
 */
@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final ReconciliationService reconciliation;
    private final ReceiptMetrics metrics;

    public TransactionService(TransactionRepository repository,
                              ReconciliationService reconciliation,
                              ReceiptMetrics metrics) {
        this.repository = repository;
        this.reconciliation = reconciliation;
        this.metrics = metrics;
    }

    /**
     * Create or update the one transaction for a receipt from an extraction result.
     *
     * Atomicity: the whole method runs in a single transaction, so header +
     * taxes + items commit together or not at all.
     *
     * Concurrency: two overlapping calls for the same receipt both try to INSERT.
     * The UNIQUE(receipt_id) constraint lets exactly one win; the loser catches
     * the integrity violation and re-reads the existing row to update it. No
     * second transaction row is ever created.
     */
    @Transactional
    public Transaction upsertFromExtraction(Long receiptId, ExtractionResult extraction) {
        Transaction txn = repository.findByReceiptId(receiptId).orElse(null);
        boolean isNew = txn == null;
        if (isNew) {
            txn = new Transaction();
            txn.setReceiptId(receiptId);
        }

        applyHeader(txn, extraction);
        applyTaxes(txn, extraction);
        applyItems(txn, extraction);
        txn.setItemizeStatus(reconciliation.statusFor(txn));

        try {
            Transaction saved = repository.saveAndFlush(txn);
            if (txn.getItemizeStatus() == ItemizeStatus.NEEDS_REVIEW) {
                metrics.reconcileMismatch();
            }
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Lost the race on INSERT: another thread created the row first.
            // Re-read and update that row instead of inserting a duplicate.
            if (!isNew) {
                throw e;
            }
            Transaction existing = repository.findByReceiptId(receiptId)
                    .orElseThrow(() -> e);
            applyHeader(existing, extraction);
            applyTaxes(existing, extraction);
            applyItems(existing, extraction);
            existing.setItemizeStatus(reconciliation.statusFor(existing));
            return repository.saveAndFlush(existing);
        }
    }

    @Transactional(readOnly = true)
    public Transaction getById(Long id) {
        Transaction txn = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + id));
        initialize(txn);
        return txn;
    }

    @Transactional(readOnly = true)
    public Transaction getByReceiptId(Long receiptId) {
        Transaction txn = repository.findByReceiptId(receiptId)
                .orElseThrow(() -> new NotFoundException("No transaction for receipt: " + receiptId));
        initialize(txn);
        return txn;
    }

    /** Touch lazy collections so they are usable after the session closes (open-in-view=false). */
    private void initialize(Transaction txn) {
        txn.getTaxes().size();
        txn.getLineItems().size();
    }

    /**
     * Re-run auto-itemize from a freshly parsed extraction. Replaces line items
     * only; merchant/date/currency/grand total/taxes are untouched.
     */
    @Transactional
    public Transaction reitemize(Long id, ExtractionResult extraction) {
        Transaction txn = getById(id);
        applyItems(txn, extraction);
        txn.setItemizeStatus(reconciliation.statusFor(txn));
        return repository.saveAndFlush(txn);
    }

    /**
     * Apply a user override of the items. If the new items + stored taxes do not
     * reconcile with grand_total, reject with 409 and persist nothing.
     */
    @Transactional
    public Transaction patchItems(Long id, List<ItemsPatchRequest.ItemInput> inputs) {
        Transaction txn = getById(id);

        List<LineItem> candidate = inputs.stream().map(in -> {
            LineItem li = new LineItem(in.description(), Money.normalize(in.amount()));
            li.setQuantity(in.quantity());
            li.setTaxAmount(in.taxAmount() == null ? null : Money.normalize(in.taxAmount()));
            return li;
        }).toList();

        BigDecimal computed = reconciliation.computedTotal(candidate, txn.getTaxes());
        if (!Money.equal(computed, txn.getGrandTotal())) {
            metrics.reconcileMismatch();
            throw new ConflictException("Patched items do not reconcile with grand total.", Map.of(
                    "grandTotal", txn.getGrandTotal(),
                    "computedTotal", computed,
                    "taxesTotal", reconciliation.taxesTotal(txn.getTaxes()),
                    "itemsTotal", reconciliation.itemsTotal(candidate)
            ));
        }

        txn.replaceLineItems(candidate);
        txn.setItemizeStatus(ItemizeStatus.COMPLETE);
        return repository.saveAndFlush(txn);
    }

    /** Confirm itemization; only allowed when items + taxes reconcile. */
    @Transactional
    public Transaction complete(Long id) {
        Transaction txn = getById(id);
        if (!reconciliation.reconciles(txn)) {
            metrics.completeRejected();
            throw new ConflictException("Cannot complete: items + taxes do not reconcile with grand total.", Map.of(
                    "grandTotal", txn.getGrandTotal(),
                    "computedTotal", reconciliation.computedTotal(txn.getLineItems(), txn.getTaxes()),
                    "itemizeStatus", txn.getItemizeStatus()
            ));
        }
        txn.setItemizeStatus(ItemizeStatus.COMPLETE);
        return repository.saveAndFlush(txn);
    }

    private void applyHeader(Transaction txn, ExtractionResult e) {
        txn.setMerchant(e.merchant());
        txn.setDate(e.date());
        txn.setCurrency(e.currency());
        txn.setGrandTotal(e.grandTotal());
    }

    private void applyTaxes(Transaction txn, ExtractionResult e) {
        List<Tax> taxes = e.taxes().stream()
                .map(t -> new Tax(t.name(), t.rate(), t.amount()))
                .toList();
        txn.replaceTaxes(taxes);
    }

    private void applyItems(Transaction txn, ExtractionResult e) {
        List<LineItem> items = e.lineItems().stream()
                .map(i -> new LineItem(i.description(), i.amount()))
                .toList();
        txn.replaceLineItems(items);
    }
}
