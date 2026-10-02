package com.navan.task.receipt;

import com.navan.task.transaction.Transaction;
import com.navan.task.transaction.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

@RestController
@RequestMapping("/receipts")
@Tag(name = "Receipts", description = "Upload, inspect, process, and delete receipts")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @Operation(summary = "Upload a receipt (multipart). Duplicate bytes return 409 with the existing receipt_id.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptResponse> upload(@RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file", e);
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        Receipt saved = receiptService.upload(
                file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename(),
                file.getContentType(),
                bytes);
        return ResponseEntity.status(HttpStatus.CREATED).body(receiptService.toResponse(saved));
    }

    @Operation(summary = "Get a stored receipt by id")
    @GetMapping("/{id}")
    public ReceiptResponse get(@PathVariable Long id) {
        return receiptService.toResponse(receiptService.get(id));
    }

    @Operation(summary = "Get stored raw OCR text (409 if not processed)")
    @GetMapping(value = "/{id}/ocr", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> getOcr(@PathVariable Long id) {
        return Map.of("rawOcrText", receiptService.getOcrText(id));
    }

    @Operation(summary = "Run OCR + extraction; create or update the one transaction (atomic, concurrency-safe)")
    @PostMapping("/{id}/process")
    public TransactionResponse process(@PathVariable Long id) {
        Transaction txn = receiptService.process(id);
        return TransactionResponse.from(txn);
    }

    @Operation(summary = "Delete an unprocessed receipt (409 if a transaction exists)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        receiptService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
