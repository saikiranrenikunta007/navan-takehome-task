package com.navan.task.transaction;

import com.navan.task.receipt.ReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
@Tag(name = "Transactions", description = "Inspect, re-itemize, override items, and complete transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final ReceiptService receiptService;

    public TransactionController(TransactionService transactionService, ReceiptService receiptService) {
        this.transactionService = transactionService;
        this.receiptService = receiptService;
    }

    @Operation(summary = "Get a transaction with taxes and line items by id")
    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable Long id) {
        return TransactionResponse.from(transactionService.getById(id));
    }

    @Operation(summary = "Get the single transaction for a receipt (404 if none)")
    @GetMapping(params = "receipt_id")
    public TransactionResponse getByReceipt(@RequestParam("receipt_id") Long receiptId) {
        return TransactionResponse.from(transactionService.getByReceiptId(receiptId));
    }

    @Operation(summary = "Re-run auto-itemize from stored OCR; replaces line items only")
    @PostMapping("/{id}/itemize")
    public TransactionResponse itemize(@PathVariable Long id) {
        return TransactionResponse.from(receiptService.reitemize(id));
    }

    @Operation(summary = "Override items; 409 if items + taxes do not reconcile (nothing persisted)")
    @PatchMapping("/{id}/items")
    public TransactionResponse patchItems(@PathVariable Long id, @Valid @RequestBody ItemsPatchRequest request) {
        return TransactionResponse.from(transactionService.patchItems(id, request.items()));
    }

    @Operation(summary = "Confirm itemization; 409 if not reconciled")
    @PostMapping("/{id}/complete")
    public TransactionResponse complete(@PathVariable Long id) {
        return TransactionResponse.from(transactionService.complete(id));
    }
}
