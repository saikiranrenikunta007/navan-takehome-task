package com.navan.task.extraction;

import com.navan.task.common.ExtractionException;
import com.navan.task.common.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the fixture receipt format into an {@link ExtractionResult}.
 *
 * Format (see fixtures/task-a):
 *   MERCHANT: <name>
 *   DATE: <yyyy-MM-dd>
 *   CURRENCY: <ISO code>
 *   <item lines: description ... amount>
 *   Subtotal <amount>
 *   VAT 19% <amount>        (or "incl. VAT 19% <amount>")
 *   TOTAL <amount>
 *
 * Rules:
 * - Header fields (merchant, date, currency, total) are mandatory. Missing any
 *   -> ExtractionException (never invent placeholder values).
 * - "Subtotal", "TOTAL", tax lines, and parenthetical notes are NOT line items.
 * - A description line with no trailing amount (e.g. "Trip fare") is not an item.
 */
@Component
public class ReceiptParser {

    private static final Pattern MERCHANT = Pattern.compile("(?im)^MERCHANT:\\s*(.+?)\\s*$");
    private static final Pattern DATE = Pattern.compile("(?im)^DATE:\\s*(\\S+)\\s*$");
    private static final Pattern CURRENCY = Pattern.compile("(?im)^CURRENCY:\\s*([A-Z]{3})\\s*$");
    private static final Pattern TOTAL = Pattern.compile("(?im)^TOTAL\\s+([0-9]+(?:\\.[0-9]+)?)\\s*$");
    // A tax line: optional "incl.", a name (VAT/GST/...), optional percentage, then an amount.
    private static final Pattern TAX = Pattern.compile(
            "(?im)^(?:incl\\.\\s*)?(VAT|GST|TAX|SALES TAX)\\s*(?:([0-9]+(?:\\.[0-9]+)?)\\s*%)?\\s+([0-9]+(?:\\.[0-9]+)?)\\s*$");
    // A generic "<description>   <amount>" line.
    private static final Pattern AMOUNT_LINE = Pattern.compile(
            "^(.*\\S)\\s{2,}([0-9]+(?:\\.[0-9]+)?)\\s*$");

    public ExtractionResult parse(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            throw new ExtractionException("Empty OCR text; cannot extract a transaction.");
        }

        String merchant = firstGroup(MERCHANT, ocrText);
        String currency = firstGroup(CURRENCY, ocrText);
        String dateRaw = firstGroup(DATE, ocrText);
        String totalRaw = firstGroup(TOTAL, ocrText);

        if (merchant == null) {
            throw new ExtractionException("Could not extract merchant.");
        }
        if (dateRaw == null) {
            throw new ExtractionException("Could not extract date.");
        }
        if (currency == null) {
            throw new ExtractionException("Could not extract currency.");
        }
        if (totalRaw == null) {
            throw new ExtractionException("Could not extract grand total.");
        }

        LocalDate date;
        try {
            date = LocalDate.parse(dateRaw);
        } catch (DateTimeParseException e) {
            throw new ExtractionException("Unparseable date: " + dateRaw);
        }

        BigDecimal grandTotal = Money.normalize(new BigDecimal(totalRaw));

        List<ExtractionResult.TaxLine> taxes = parseTaxes(ocrText);
        List<ExtractionResult.ItemLine> items = parseLineItems(ocrText);

        return new ExtractionResult(merchant, date, currency, grandTotal, taxes, items);
    }

    private List<ExtractionResult.TaxLine> parseTaxes(String ocrText) {
        List<ExtractionResult.TaxLine> taxes = new ArrayList<>();
        Matcher m = TAX.matcher(ocrText);
        while (m.find()) {
            String name = m.group(1).toUpperCase();
            BigDecimal rate = null;
            if (m.group(2) != null) {
                // "19%" is stored as the fraction 0.19 to match gold.json.
                rate = new BigDecimal(m.group(2)).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            }
            BigDecimal amount = Money.normalize(new BigDecimal(m.group(3)));
            taxes.add(new ExtractionResult.TaxLine(name, rate, amount));
        }
        return taxes;
    }

    private List<ExtractionResult.ItemLine> parseLineItems(String ocrText) {
        List<ExtractionResult.ItemLine> items = new ArrayList<>();
        for (String line : ocrText.split("\\R")) {
            Matcher m = AMOUNT_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String desc = m.group(1).trim();
            String upper = desc.toUpperCase();
            // Skip header totals and tax lines; those are not auto-itemize items.
            if (upper.equals("SUBTOTAL") || upper.equals("TOTAL")) {
                continue;
            }
            if (TAX.matcher(line).matches()) {
                continue;
            }
            BigDecimal amount = Money.normalize(new BigDecimal(m.group(2)));
            items.add(new ExtractionResult.ItemLine(desc, amount));
        }
        return items;
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
