package com.navan.task.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics for the receipt pipeline.
 *
 * Metrics are registered on the Micrometer {@link MeterRegistry}, exported to
 * an OpenTelemetry collector via the OTLP registry, and simultaneously visible
 * through Spring Boot Actuator ({@code /actuator/metrics/<name>}).
 */
@Component
public class ReceiptMetrics {

    private final Counter receiptsUploaded;
    private final Counter duplicateUploads;
    private final Counter processSucceeded;
    private final Counter processFailed;
    private final Counter reconcileMismatch;
    private final Counter completeRejected;

    public ReceiptMetrics(MeterRegistry registry) {
        this.receiptsUploaded = Counter.builder("receipts.uploaded")
                .description("Receipts stored via POST /receipts").register(registry);
        this.duplicateUploads = Counter.builder("receipts.duplicate")
                .description("Rejected duplicate uploads (same file bytes)").register(registry);
        this.processSucceeded = Counter.builder("receipts.process.succeeded")
                .description("Successful process runs").register(registry);
        this.processFailed = Counter.builder("receipts.process.failed")
                .description("Process runs that failed extraction").register(registry);
        this.reconcileMismatch = Counter.builder("transactions.reconcile.mismatch")
                .description("Itemization did not reconcile with grand total").register(registry);
        this.completeRejected = Counter.builder("transactions.complete.rejected")
                .description("Complete attempts rejected because not reconciled").register(registry);
    }

    public void receiptUploaded() {
        receiptsUploaded.increment();
    }

    public void duplicateUpload() {
        duplicateUploads.increment();
    }

    public void processSucceeded() {
        processSucceeded.increment();
    }

    public void processFailed() {
        processFailed.increment();
    }

    public void reconcileMismatch() {
        reconcileMismatch.increment();
    }

    public void completeRejected() {
        completeRejected.increment();
    }
}
