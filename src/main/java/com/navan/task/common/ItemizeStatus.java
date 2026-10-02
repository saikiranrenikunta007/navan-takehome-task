package com.navan.task.common;

/**
 * Itemization lifecycle for a transaction.
 *
 * COMPLETE      - line items + taxes reconcile with grand_total and user confirmed.
 * NEEDS_REVIEW  - extracted, but items + taxes do not sum to grand_total (or no reliable items).
 * FAILED        - extraction could not produce a usable transaction header.
 */
public enum ItemizeStatus {
    COMPLETE,
    NEEDS_REVIEW,
    FAILED
}
