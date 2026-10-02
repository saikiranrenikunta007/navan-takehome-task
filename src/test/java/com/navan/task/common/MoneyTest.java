package com.navan.task.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money correctness is the thing an expense-management system cannot get wrong.
 * These tests pin the two guarantees the rest of the service relies on:
 * scale-2 HALF_UP normalization, and value-based (not representation-based)
 * equality so that reconciliation never fails on trailing-zero differences.
 */
class MoneyTest {

    @Test
    void normalizeRoundsToScaleTwoHalfUp() {
        // .125 rounds half up to .13; .124 rounds down to .12.
        assertThat(Money.normalize(new BigDecimal("1.125"))).isEqualByComparingTo("1.13");
        assertThat(Money.normalize(new BigDecimal("1.124"))).isEqualByComparingTo("1.12");
        // Exact value pads to two decimal places, never binary-float drift.
        assertThat(Money.normalize(new BigDecimal("17.8"))).isEqualByComparingTo("17.80");
    }

    @Test
    void equalComparesByValueNotRepresentation() {
        // 17.85, 17.850 and 17.8500 are the same money.
        assertThat(Money.equal(new BigDecimal("17.85"), new BigDecimal("17.8500"))).isTrue();
        assertThat(Money.equal(new BigDecimal("17.850"), new BigDecimal("17.85"))).isTrue();
        assertThat(Money.equal(new BigDecimal("17.85"), new BigDecimal("17.86"))).isFalse();
    }

    @Test
    void repeatedDecimalAdditionStaysExactAtScaleTwo() {
        // 0.10 + 0.20 is exactly 0.30 here; IEEE float would give 0.30000000000000004.
        BigDecimal sum = Money.normalize(new BigDecimal("0.10").add(new BigDecimal("0.20")));
        assertThat(sum).isEqualByComparingTo("0.30");
        assertThat(Money.equal(sum, new BigDecimal("0.30"))).isTrue();
    }
}
