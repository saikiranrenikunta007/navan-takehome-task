package com.navan.task.receipt;

import com.navan.task.common.ConflictException;
import com.navan.task.common.NotFoundException;
import com.navan.task.extraction.ExtractionResult;
import com.navan.task.extraction.OcrService;
import com.navan.task.extraction.ReceiptParser;
import com.navan.task.observability.ReceiptMetrics;
import com.navan.task.storage.StorageService;
import com.navan.task.transaction.Transaction;
import com.navan.task.transaction.TransactionRepository;
import com.navan.task.transaction.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Receipt lifecycle: upload (with byte-level dedupe), process (OCR + extract +
 * atomic transaction upsert), read, OCR read, and delete.
 */
@Service
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final StorageService storageService;
    private final OcrService ocrService;
    private final ReceiptParser parser;
    private final ReceiptMetrics metrics;

    public ReceiptService(ReceiptRepository receiptRepository,
                          TransactionRepository transactionRepository,
                          TransactionService transactionService,
                          StorageService storageService,
                          OcrService ocrService,
                          ReceiptParser parser,
                          ReceiptMetrics metrics) {
        this.receiptRepository = receiptRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.storageService = storageService;
        this.ocrService = ocrService;
        this.parser = parser;
        this.metrics = metrics;
    }

    /**
     * Store a receipt. Identical file bytes are deduped: we return 409 with the
     * existing receipt_id rather than creating a second processable receipt.
     */
    @Transactional
    public Receipt upload(String originalName, String contentType, byte[] bytes) {
        String sha = sha256(bytes);
        receiptRepository.findByContentSha256(sha).ifPresent(existing -> {
            metrics.duplicateUpload();
            throw new ConflictException("Receipt with identical content already exists.",
                    Map.of("receiptId", existing.getId()));
        });

        String storageKey = storageService.store(sha, contentType, bytes);

        Receipt receipt = new Receipt();
        receipt.setOriginalName(originalName);
        receipt.setContentType(contentType);
        receipt.setContentSha256(sha);
        receipt.setStorageKey(storageKey);
        receipt.setProcessed(false);
        Receipt saved = receiptRepository.save(receipt);
        metrics.receiptUploaded();
        return saved;
    }

    @Transactional(readOnly = true)
    public Receipt get(Long id) {
        return receiptRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Receipt not found: " + id));
    }

    public ReceiptResponse toResponse(Receipt r) {
        Long txnId = transactionRepository.findByReceiptId(r.getId())
                .map(Transaction::getId)
                .orElse(null);
        return new ReceiptResponse(r.getId(), r.getOriginalName(), r.getUploadedAt(), r.isProcessed(), txnId);
    }

    /** Return stored raw OCR text; 409 if process has not run yet. */
    @Transactional(readOnly = true)
    public String getOcrText(Long id) {
        Receipt r = get(id);
        if (!r.isProcessed() || r.getRawOcrText() == null) {
            throw new ConflictException("Receipt has not been processed yet.");
        }
        return r.getRawOcrText();
    }

    /**
     * Run OCR + extraction and upsert the single transaction for this receipt.
     * Storing raw OCR on the receipt and the structured transaction happen in
     * one @Transactional unit (atomic). Re-running updates in place.
     */
    @Transactional
    public Transaction process(Long id) {
        Receipt receipt = get(id);
        byte[] bytes = storageService.retrieve(receipt.getStorageKey());
        String ocrText = ocrService.extractText(bytes);

        ExtractionResult extraction;
        try {
            extraction = parser.parse(ocrText);
        } catch (RuntimeException e) {
            metrics.processFailed();
            throw e;
        }

        receipt.setRawOcrText(ocrText);
        receipt.setProcessed(true);
        receiptRepository.save(receipt);

        Transaction txn = transactionService.upsertFromExtraction(id, extraction);
        metrics.processSucceeded();
        return txn;
    }

    /**
     * Re-run auto-itemize for a transaction using its receipt's STORED OCR
     * (no re-upload). Replaces line items only.
     */
    @Transactional
    public Transaction reitemize(Long transactionId) {
        Transaction txn = transactionService.getById(transactionId);
        Receipt receipt = get(txn.getReceiptId());
        if (receipt.getRawOcrText() == null) {
            throw new ConflictException("Receipt has no stored OCR to re-itemize.");
        }
        ExtractionResult extraction = parser.parse(receipt.getRawOcrText());
        return transactionService.reitemize(transactionId, extraction);
    }

    /**
     * Delete an unprocessed receipt (row + stored file). If a transaction exists
     * for this receipt, return 409 and delete nothing.
     */
    @Transactional
    public void delete(Long id) {        Receipt receipt = get(id);
        if (transactionRepository.findByReceiptId(id).isPresent()) {
            throw new ConflictException("Receipt has a transaction and cannot be deleted.");
        }
        storageService.delete(receipt.getStorageKey());
        receiptRepository.delete(receipt);
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
