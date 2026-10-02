package com.navan.task.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money helpers. All monetary values use scale 2, HALF_UP rounding.
 * We never use IEEE float for money.
 */
public final class Money {

    public static final int SCALE = 2;

    private Money() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Equality at scale 2 (ignores trailing-zero representation differences). */
    public static boolean equal(BigDecimal a, BigDecimal b) {
        return normalize(a).compareTo(normalize(b)) == 0;
    }
}
