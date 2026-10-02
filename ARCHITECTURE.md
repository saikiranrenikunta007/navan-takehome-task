# Architecture

One page on the three things that matter here: receipt-to-transaction identity,
why concurrent `process` is safe, and why `process` is atomic.

## Data model

```
receipts (1) ----< (1) transactions (1) ----< (N) taxes
                                       (1) ----< (N) line_items
```

- `receipts.content_sha256` has a `UNIQUE` constraint. The dedupe key is the
  SHA-256 of the uploaded bytes.
- `transactions.receipt_id` has a `UNIQUE` constraint and a foreign key to
  `receipts`. This is what makes "one receipt, one transaction" a database
  guarantee rather than an application convention.
- `taxes` and `line_items` are children of a transaction with
  `ON DELETE CASCADE`.
- Money is `DECIMAL(19,2)`; tax rate is `DECIMAL(9,4)` stored as a fraction.

Schema lives in `src/main/resources/db/migration/V1__init_schema.sql` and is
applied by Flyway. JPA runs with `ddl-auto: validate`, so the entities must
match the migrated schema; the database owns the schema, not Hibernate.

## Receipt to transaction identity

`POST /receipts/{id}/process` creates or updates the single transaction for a
receipt. The flow:

1. Load the receipt and its stored bytes.
2. Run OCR (stub) then parse into an `ExtractionResult`.
3. Look up an existing transaction by `receipt_id`.
    - None: build a new one.
    - Exists: update it in place.
4. Replace taxes and line items, set `itemize_status`, save.

Because the lookup-or-create keys on `receipt_id`, re-processing the same
receipt never produces a second transaction. The children are fully replaced on
each run (`orphanRemoval = true`), so re-processing does not accumulate
duplicate tax or item rows. The sequential re-process test asserts both the
stable transaction id and the row counts.

## Why concurrent `process` is safe

Two overlapping `process` calls for the same receipt can both find "no existing
transaction" and both try to INSERT. The `UNIQUE (receipt_id)` constraint lets
exactly one INSERT succeed. The loser's INSERT fails with a
`DataIntegrityViolationException`, which `TransactionService.upsertFromExtraction`
catches; it then re-reads the row the winner created and applies its update to
that same row.

The net result is one transaction regardless of interleaving, enforced by the
database. There is no application-level lock, no Redis, no queue. The
`concurrentProcessCreatesOnlyOneTransaction` test fires several threads at the
same receipt and asserts exactly one transaction exists.

This is optimistic: we let the write race and resolve the conflict, rather than
serialising upfront. For a single-row-per-receipt invariant that is simpler and
correct.

## Why `process` is atomic

`ReceiptService.process` is annotated `@Transactional`. Within one transaction
it:

- marks the receipt processed and stores the raw OCR text, and
- upserts the transaction header, its taxes, and its line items.

All of that commits together or rolls back together. There is no window where
taxes are persisted but items are not, or where a receipt is flagged processed
without its transaction. If extraction fails, the transaction rolls back and the
receipt stays unprocessed; a `process.failed` metric is incremented.

## Reconciliation and status

`ReconciliationService` computes `sum(line_items) + sum(taxes)` at scale 2 and
compares it to `grand_total`.

- Reconciles and user confirms: `COMPLETE`.
- Does not reconcile, or no items: `NEEDS_REVIEW`.
- Extraction cannot produce a header: the request fails with 422; no transaction
  row is written (`FAILED` is reserved for that class of outcome).

`PATCH /transactions/{id}/items` recomputes against the stored taxes and
`grand_total`. If the override does not reconcile it returns 409 with the
mismatch numbers and persists nothing. `POST /complete` applies the same check
and refuses to force a `NEEDS_REVIEW` transaction to `COMPLETE`. We never add a
balancing line and never rewrite `grand_total`.

## Storage abstraction (SOLID)

Callers depend only on the `StorageService` interface (dependency inversion).
Two adapters implement it:

- `LocalStorageService` - filesystem, the default, used by tests and local runs.
- `FlociiS3MultipartStorageService` - Flocii S3-compatible store via the S3
  multipart upload protocol.

The active adapter is chosen by `storage.provider` with Spring's
`@ConditionalOnProperty`, so adding a backend means adding a class, not editing
callers (open/closed).

## Observability

`ReceiptMetrics` registers Micrometer counters for the business events that
matter (uploads, duplicates, process success/failure, reconcile mismatch,
complete rejected). The Micrometer OTLP registry pushes them to an
OpenTelemetry collector, and Actuator exposes the same meters at
`/actuator/metrics` for quick inspection without a backend.
