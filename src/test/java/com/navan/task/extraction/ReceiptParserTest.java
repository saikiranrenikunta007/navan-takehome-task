package com.navan.task.extraction;

import com.navan.task.common.ExtractionException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptParserTest {

    private final ReceiptParser parser = new ReceiptParser();

    private String fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void cleanFixtureMatchesGoldFields() {
        ExtractionResult r = parser.parse(fixture("receipt-clean.txt"));

        assertThat(r.merchant()).isEqualTo("Cafe Mitte");
        assertThat(r.date()).isEqualTo(LocalDate.parse("2026-03-12"));
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.grandTotal()).isEqualByComparingTo("17.85");

        assertThat(r.taxes()).hasSize(1);
        assertThat(r.taxes().get(0).name()).isEqualTo("VAT");
        assertThat(r.taxes().get(0).rate()).isEqualByComparingTo("0.19");
        assertThat(r.taxes().get(0).amount()).isEqualByComparingTo("2.85");

        assertThat(r.lineItems()).extracting(ExtractionResult.ItemLine::description)
                .containsExactly("Espresso", "Sandwich", "Mineral water");
        assertThat(r.lineItems()).extracting(ExtractionResult.ItemLine::amount)
                .containsExactly(new BigDecimal("3.50"), new BigDecimal("8.90"), new BigDecimal("2.60"));
    }

    @Test
    void taxOnlyFixtureHasTaxButNoItems() {
        ExtractionResult r = parser.parse(fixture("receipt-tax-only.txt"));

        assertThat(r.merchant()).isEqualTo("Berlin Taxi GmbH");
        assertThat(r.grandTotal()).isEqualByComparingTo("24.00");
        assertThat(r.taxes()).hasSize(1);
        assertThat(r.taxes().get(0).amount()).isEqualByComparingTo("3.83");
        // "Trip fare" has no amount, so it is not an item.
        assertThat(r.lineItems()).isEmpty();
    }

    @Test
    void mismatchFixtureItemsDoNotSumToTotal() {
        ExtractionResult r = parser.parse(fixture("receipt-mismatch.txt"));

        assertThat(r.merchant()).isEqualTo("Hotel Shop");
        assertThat(r.grandTotal()).isEqualByComparingTo("18.50");
        assertThat(r.lineItems()).extracting(ExtractionResult.ItemLine::description)
                .containsExactly("Water", "Snacks");
        assertThat(r.taxes().get(0).amount()).isEqualByComparingTo("1.90");
    }

    @Test
    void missingHeaderFieldFails() {
        assertThatThrownBy(() -> parser.parse("DATE: 2026-01-01\nCURRENCY: EUR\nTOTAL 10.00"))
                .isInstanceOf(ExtractionException.class);
    }
}
