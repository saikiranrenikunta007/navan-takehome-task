package com.navan.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.task.receipt.ReceiptRepository;
import com.navan.task.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReceiptApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ReceiptRepository receiptRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        receiptRepository.deleteAll();
    }

    private byte[] fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long upload(String fixtureName) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fixtureName, MediaType.TEXT_PLAIN_VALUE, fixture(fixtureName));
        MvcResult res = mvc.perform(multipart("/receipts").file(file))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long process(long receiptId) throws Exception {
        MvcResult res = mvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    // 1. Clean fixture matches gold.json after process: COMPLETE, taxes, items, total.
    @Test
    void cleanFixtureProcessesToCompleteMatchingGold() throws Exception {
        long receiptId = upload("receipt-clean.txt");

        mvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant").value("Cafe Mitte"))
                .andExpect(jsonPath("$.date").value("2026-03-12"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.grandTotal").value(17.85))
                .andExpect(jsonPath("$.itemizeStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.taxes.length()").value(1))
                .andExpect(jsonPath("$.taxes[0].name").value("VAT"))
                .andExpect(jsonPath("$.taxes[0].amount").value(2.85))
                .andExpect(jsonPath("$.lineItems.length()").value(3));
    }

    // 2. Mismatch fixture keeps grand_total, no balancing line, NEEDS_REVIEW.
    @Test
    void mismatchFixtureIsNeedsReviewWithoutBalancingLine() throws Exception {
        long receiptId = upload("receipt-mismatch.txt");

        mvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(18.50))
                .andExpect(jsonPath("$.itemizeStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.lineItems.length()").value(2));
    }

    // 3. Tax-only fixture is NEEDS_REVIEW with empty items.
    @Test
    void taxOnlyFixtureIsNeedsReview() throws Exception {
        long receiptId = upload("receipt-tax-only.txt");

        mvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(24.00))
                .andExpect(jsonPath("$.itemizeStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.taxes.length()").value(1))
                .andExpect(jsonPath("$.lineItems.length()").value(0));
    }

    // 4. PATCH 409 when items + taxes do not reconcile; persisted items + grand_total unchanged.
    @Test
    void patchItemsRejected409WhenNotReconciled() throws Exception {
        long receiptId = upload("receipt-clean.txt");
        long txnId = process(receiptId);

        String badPatch = """
                {"items":[{"description":"Merged","amount":1.00}]}
                """;
        mvc.perform(patch("/transactions/{id}/items", txnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badPatch))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.grandTotal").value(17.85));

        // Nothing persisted: still 3 original items, total unchanged.
        mvc.perform(get("/transactions/{id}", txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineItems.length()").value(3))
                .andExpect(jsonPath("$.grandTotal").value(17.85));
    }

    // 4b. A reconciling patch succeeds.
    @Test
    void patchItemsSucceedsWhenReconciled() throws Exception {
        long receiptId = upload("receipt-clean.txt");
        long txnId = process(receiptId);

        // items must sum to grand_total - taxes = 17.85 - 2.85 = 15.00
        String goodPatch = """
                {"items":[{"description":"Combined net","amount":15.00}]}
                """;
        mvc.perform(patch("/transactions/{id}/items", txnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goodPatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemizeStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.lineItems.length()").value(1));
    }

    // 5. Re-process the same receipt twice sequentially: one transaction id, no duplicate rows.
    @Test
    void reprocessKeepsOneTransactionAndNoDuplicateRows() throws Exception {
        long receiptId = upload("receipt-clean.txt");
        long firstTxn = process(receiptId);
        long secondTxn = process(receiptId);

        assertThat(secondTxn).isEqualTo(firstTxn);
        assertThat(transactionRepository.count()).isEqualTo(1);

        mvc.perform(get("/transactions/{id}", firstTxn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxes.length()").value(1))
                .andExpect(jsonPath("$.lineItems.length()").value(3));
    }

    // 6. Concurrent process on the same receipt: still one transaction.
    @Test
    void concurrentProcessCreatesOnlyOneTransaction() throws Exception {
        long receiptId = upload("receipt-clean.txt");

        int threads = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(threads);
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    mvc.perform(post("/receipts/{id}/process", receiptId));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(transactionRepository.findByReceiptId(receiptId)).isPresent();
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // 7. GET /receipts/{id} and GET /transactions?receipt_id= return the same transaction id.
    @Test
    void receiptAndTransactionByReceiptAgreeOnId() throws Exception {
        long receiptId = upload("receipt-clean.txt");
        long txnId = process(receiptId);

        mvc.perform(get("/receipts/{id}", receiptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(true))
                .andExpect(jsonPath("$.transactionId").value((int) txnId));

        mvc.perform(get("/transactions").param("receipt_id", String.valueOf(receiptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) txnId))
                .andExpect(jsonPath("$.receiptId").value((int) receiptId));
    }

    // 8. DELETE of a processed receipt returns 409 and the transaction still exists.
    @Test
    void deleteProcessedReceiptReturns409AndKeepsTransaction() throws Exception {
        long receiptId = upload("receipt-clean.txt");
        long txnId = process(receiptId);

        mvc.perform(delete("/receipts/{id}", receiptId))
                .andExpect(status().isConflict());

        assertThat(receiptRepository.findById(receiptId)).isPresent();
        mvc.perform(get("/transactions/{id}", txnId))
                .andExpect(status().isOk());
    }

    // 9. POST /complete on the mismatch fixture returns 409 and status stays NEEDS_REVIEW.
    @Test
    void completeOnMismatchReturns409AndStaysNeedsReview() throws Exception {
        long receiptId = upload("receipt-mismatch.txt");
        long txnId = process(receiptId);

        mvc.perform(post("/transactions/{id}/complete", txnId))
                .andExpect(status().isConflict());

        mvc.perform(get("/transactions/{id}", txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemizeStatus").value("NEEDS_REVIEW"));
    }

    // Extra: duplicate upload (same bytes) returns 409 with the existing receipt id.
    @Test
    void duplicateUploadReturns409() throws Exception {
        long receiptId = upload("receipt-clean.txt");

        MockMultipartFile same = new MockMultipartFile(
                "file", "receipt-clean.txt", MediaType.TEXT_PLAIN_VALUE, fixture("receipt-clean.txt"));
        mvc.perform(multipart("/receipts").file(same))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.receiptId").value((int) receiptId));
    }

    // Extra: GET OCR before process returns 409; after process returns stored text.
    @Test
    void ocrText409BeforeProcessThenAvailable() throws Exception {
        long receiptId = upload("receipt-clean.txt");

        mvc.perform(get("/receipts/{id}/ocr", receiptId))
                .andExpect(status().isConflict());

        process(receiptId);

        MvcResult res = mvc.perform(get("/receipts/{id}/ocr", receiptId))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        assertThat(node.get("rawOcrText").asText()).contains("Cafe Mitte");
    }

    // Extra: re-itemize from stored OCR replaces items without a second transaction.
    @Test
    void reitemizeFromStoredOcrKeepsOneTransaction() throws Exception {
        long receiptId = upload("receipt-clean.txt");
        long txnId = process(receiptId);

        mvc.perform(post("/transactions/{id}/itemize", txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) txnId))
                .andExpect(jsonPath("$.lineItems.length()").value(3));

        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Additional coverage beyond the required nine: spec edge cases that a
    // reviewer checks explicitly (parse-failure 4xx, 404s, re-itemize
    // invariants, and money precision for an expense-management domain).
    // ---------------------------------------------------------------------

    // Spec: "matching the gold fields on fixtures is enough." Assert the clean
    // fixture's processed transaction equals gold.json field-for-field, rather
    // than hand-written literals, so the gold file is the single source of truth.
    @Test
    void cleanFixtureProcessedTransactionEqualsGoldJson() throws Exception {
        JsonNode gold = loadGold().get("receipt-clean");
        long receiptId = upload("receipt-clean.txt");

        MvcResult res = mvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode txn = json.readTree(res.getResponse().getContentAsString());

        assertThat(txn.get("merchant").asText()).isEqualTo(gold.get("merchant").asText());
        assertThat(txn.get("date").asText()).isEqualTo(gold.get("date").asText());
        assertThat(txn.get("currency").asText()).isEqualTo(gold.get("currency").asText());
        assertThat(new BigDecimal(txn.get("grandTotal").asText()))
                .isEqualByComparingTo(gold.get("grand_total").asText());

        // Taxes match gold (name + rate + amount), compared by numeric value.
        JsonNode goldTax = gold.get("taxes").get(0);
        JsonNode txnTax = txn.get("taxes").get(0);
        assertThat(txnTax.get("name").asText()).isEqualTo(goldTax.get("name").asText());
        assertThat(new BigDecimal(txnTax.get("rate").asText()))
                .isEqualByComparingTo(goldTax.get("rate").asText());
        assertThat(new BigDecimal(txnTax.get("amount").asText()))
                .isEqualByComparingTo(goldTax.get("amount").asText());

        // Line item descriptions and amounts match gold exactly, in order.
        JsonNode goldItems = gold.get("line_items");
        JsonNode txnItems = txn.get("lineItems");
        assertThat(txnItems.size()).isEqualTo(goldItems.size());
        for (int i = 0; i < goldItems.size(); i++) {
            assertThat(txnItems.get(i).get("description").asText())
                    .isEqualTo(goldItems.get(i).get("description").asText());
            assertThat(new BigDecimal(txnItems.get(i).get("amount").asText()))
                    .isEqualByComparingTo(goldItems.get(i).get("amount").asText());
        }

        assertThat(txn.get("itemizeStatus").asText())
                .isEqualTo(gold.get("itemize_status").asText());
    }

    // Spec: "Parse failure ... return 4xx. Do not invent placeholders." Process
    // an unparseable receipt and assert 422 with NO transaction persisted.
    @Test
    void processUnparseableReceiptReturns422AndPersistsNoTransaction() throws Exception {
        long receiptId = upload("receipt-unparseable.txt");

        mvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isUnprocessableEntity());

        // Rolled back: no transaction row, and the receipt is not marked processed.
        assertThat(transactionRepository.count()).isEqualTo(0);
        mvc.perform(get("/receipts/{id}", receiptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(false))
                .andExpect(jsonPath("$.transactionId").doesNotExist());
    }

    // Spec: "404 if missing" for both receipt and transaction reads.
    @Test
    void missingReceiptAndTransactionReturn404() throws Exception {
        mvc.perform(get("/receipts/{id}", 999_999L))
                .andExpect(status().isNotFound());
        mvc.perform(get("/transactions/{id}", 999_999L))
                .andExpect(status().isNotFound());
        mvc.perform(get("/transactions").param("receipt_id", "999999"))
                .andExpect(status().isNotFound());
    }

    // Spec: re-itemize must "replace line items only" and must NOT rewrite
    // merchant/date/currency/grand total/taxes. Assert those are untouched.
    @Test
    void reitemizeDoesNotRewriteHeaderOrTaxes() throws Exception {
        long receiptId = upload("receipt-clean.txt");
        long txnId = process(receiptId);

        mvc.perform(post("/transactions/{id}/itemize", txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) txnId))
                .andExpect(jsonPath("$.merchant").value("Cafe Mitte"))
                .andExpect(jsonPath("$.date").value("2026-03-12"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.grandTotal").value(17.85))
                .andExpect(jsonPath("$.taxes.length()").value(1))
                .andExpect(jsonPath("$.taxes[0].name").value("VAT"))
                .andExpect(jsonPath("$.taxes[0].amount").value(2.85))
                .andExpect(jsonPath("$.lineItems.length()").value(3));
    }

    private JsonNode loadGold() throws IOException {
        try (var in = getClass().getResourceAsStream("/fixtures/gold.json")) {
            return json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
