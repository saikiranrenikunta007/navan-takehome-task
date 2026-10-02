# Architecture

This document explains how the receipt auto-itemize service is built: the data
model, the request flows, the three correctness guarantees the brief calls out
(receipt-to-transaction identity, concurrency safety, atomicity), the design
decisions behind the ambiguous rules, and how the service would behave in
production. It is meant to be read top to bottom in a few minutes.

## 1. Overview

A client uploads a receipt. The service stores the bytes, runs OCR plus
extraction, and produces exactly one transaction per receipt with its taxes and
auto-itemized line items. If the items plus taxes do not add up to the printed
total, the transaction is kept and flagged `NEEDS_REVIEW`; the service never
invents a balancing line and never rewrites the printed total.

- Java 21, Spring Boot 3, JPA/Hibernate, Flyway.
- MySQL 8 in production; a file-backed H2 database in tests (never an in-memory
  engine, so the database constraints are exercised for real).
- Storage behind a `StorageService` interface with a local-filesystem adapter
  (default) and a Flocii S3-compatible adapter using S3 multipart upload.
- Micrometer metrics exported over OTLP and via Actuator.

## 2. Component map

```
            ┌──────────────────────── HTTP layer ────────────────────────┐
            │  ReceiptController      TransactionController   HealthCtrl  │
            └──────┬───────────────────────┬──────────────────────────────┘
                   │                        │
                   ▼                        ▼
            ┌──────────────────────── Service layer ─────────────────────┐
            │                                                             │
            │   ReceiptService ───────────────┐                          │
            │     │  │  │  │                   │                          │
            │     │  │  │  └──► StorageService │                          │
            │     │  │  └─────► ReceiptParser  │                          │
            │     │  └────────► OcrService     ▼                          │
            │     │             TransactionService ──► ReconciliationSvc  │
            │     └──────────────────┐         │                          │
            └────────────────────────┼─────────┼──────────────────────────┘
                                      │         │
                                      ▼         ▼
                            ┌──────────────────────────┐
                            │     JPA repositories      │
                            └─────────────┬────────────┘
                                          │
                                          ▼
                            ┌──────────────────────────┐
                            │  Database (Flyway schema) │
                            │  receipts · transactions  │
                            │  taxes · line_items       │
                            └──────────────────────────┘
```

Packages are organised by feature (`receipt`, `transaction`, `extraction`,
`storage`, `common`, `observability`) rather than by technical layer, so all
code for one concept lives together and internals can stay package-private.

### 2.1 Sequence: `POST /receipts/{id}/process`

The happy path and the parse-failure branch, in call order. The whole method is
one database transaction, so a failure rolls everything back.

```
Client   ReceiptController   ReceiptService   Storage   Ocr   Parser   TransactionService   DB
  │             │                  │             │       │       │             │             │
  │  POST       │                  │             │       │       │             │             │
  │ /process    │                  │             │       │       │             │             │
  ├────────────►│  process(id)     │             │       │       │             │             │
  │             ├─────────────────►│             │       │       │             │             │
  │             │                  │ retrieve()  │       │       │             │             │
  │             │                  ├────────────►│       │       │             │             │
  │             │                  │   bytes     │       │       │             │             │
  │             │                  │◄────────────┤       │       │             │             │
  │             │                  │ extractText(bytes)  │       │             │             │
  │             │                  ├────────────────────►│       │             │             │
  │             │                  │   ocrText           │       │             │             │
  │             │                  │◄────────────────────┤       │             │             │
  │             │                  │ parse(ocrText)      │       │             │             │
  │             │                  ├────────────────────────────►│             │             │
  │             │                  │                     │       │             │             │
  │             │                  │  (a) ExtractionResult        │             │             │
  │             │                  │◄────────────────────────────┤             │             │
  │             │                  │ save raw OCR + processed=true│             │             │
  │             │                  ├─────────────────────────────────────────────────────────►│
  │             │                  │ upsertFromExtraction(id, result)           │             │
  │             │                  ├───────────────────────────────────────────►│             │
  │             │                  │                              findByReceiptId / INSERT     │
  │             │                  │                                            ├────────────►│
  │             │                  │                              reconcile -> status         │
  │             │                  │   transaction                              │             │
  │             │                  │◄───────────────────────────────────────────┤             │
  │  200 + txn  │                  │  (processSucceeded counter++)              │             │
  │◄────────────┤◄─────────────────┤                                            │  COMMIT     │
  │             │                  │                                            │────────────►│
  │             │                  │                                            │             │
  │  (b) parse fails: Parser throws ExtractionException                         │             │
  │             │                  │  processFailed counter++, rethrow          │             │
  │  422        │                  │  (transaction ROLLS BACK: no rows written) │             │
  │◄────────────┤◄─────────────────┤                                            │  ROLLBACK   │
```

### 2.2 Sequence: concurrent `process` on the same receipt

Two threads race. The `UNIQUE (receipt_id)` constraint lets one INSERT win; the
loser catches the violation, re-reads the winner's row, and updates it. The
result is always one transaction - enforced by the database, no lock.

```
Thread A (process)          DB  (UNIQUE receipt_id)          Thread B (process)
      │                      │                                     │
      │ findByReceiptId -> none                        none <- findByReceiptId
      ├─────────────────────►│◄────────────────────────────────────┤
      │                      │                                     │
      │ INSERT transaction   │                                     │
      ├─────────────────────►│                                     │
      │        OK (winner)   │   INSERT transaction (same receipt) │
      │◄─────────────────────┤◄────────────────────────────────────┤
      │                      │   UNIQUE violation ─────────────────►│
      │                      │                                     │ catch
      │                      │   findByReceiptId -> winner's row    │ DataIntegrity-
      │                      │◄────────────────────────────────────┤ ViolationException
      │                      │   UPDATE that same row               │
      │                      │◄────────────────────────────────────┤
      │ COMMIT               │                      COMMIT          │
      ├─────────────────────►│◄────────────────────────────────────┤
      │                      │                                     │
            Net result: exactly ONE transaction row for the receipt.
```

### 2.3 Diagram legend

- **Boxes** are tables (data model) or components (component map).
- `PK` primary key · `FK` foreign key · `UK` unique key.
- `1 : 1` / `1 : N` are relationship cardinalities.
- In the sequence diagrams, each vertical bar is one participant's timeline;
  arrows are calls or returns, read top to bottom.
- `COMMIT` / `ROLLBACK` mark the single database transaction boundary for
  `process`: everything inside it persists together or not at all.

## 3. Data model

One receipt maps to exactly one transaction; a transaction owns many taxes and
many line items.

```
┌─────────────────────────────┐
│ receipts                    │
├─────────────────────────────┤
│ PK  id                      │
│     original_name           │
│     content_type            │
│ UK  content_sha256          │  SHA-256 dedupe key
│     storage_key             │
│     raw_ocr_text            │
│     processed               │
│     uploaded_at             │
└──────────────┬──────────────┘
               │  1 : 1   (UNIQUE receipt_id)
               ▼
┌─────────────────────────────┐
│ transactions                │
├─────────────────────────────┤
│ PK  id                      │
│ FK  receipt_id   (UNIQUE)   │  one receipt -> one transaction
│     merchant                │
│     txn_date                │
│     currency       VARCHAR(3)
│     grand_total    DECIMAL(19,2)
│     itemize_status          │  COMPLETE | NEEDS_REVIEW | FAILED
│     created_at              │
│     updated_at              │
└──────┬───────────────┬──────┘
       │ 1 : N         │ 1 : N
       ▼               ▼
┌───────────────┐  ┌──────────────────────┐
│ taxes         │  │ line_items           │
├───────────────┤  ├──────────────────────┤
│ PK  id        │  │ PK  id               │
│ FK  txn_id    │  │ FK  txn_id           │
│     name      │  │     description      │
│     rate      │  │     amount           │
│       DECIMAL(9,4) │   DECIMAL(19,2)    │
│     amount    │  │     quantity         │
│       DECIMAL(19,2)│   tax_amount       │
└───────────────┘  └──────────────────────┘
   (ON DELETE CASCADE from transactions)
```

- `receipts.content_sha256` has a `UNIQUE` constraint. The dedupe key is the
  SHA-256 of the uploaded bytes.
- `transactions.receipt_id` has a `UNIQUE` constraint and a foreign key to
  `receipts`. This is what makes "one receipt, one transaction" a database
  guarantee rather than an application convention.
- `taxes` and `line_items` are children of a transaction with
  `ON DELETE CASCADE`.
- Money is `DECIMAL(19,2)`; tax rate is `DECIMAL(9,4)` stored as a fraction
  (for example `0.1900`).

The schema lives in `src/main/resources/db/migration/V1__init_schema.sql` and is
applied by Flyway. JPA runs with `ddl-auto: validate`, so the entities must
match the migrated schema: the database owns the schema, not Hibernate.

## 4. Key flows

### Upload (`POST /receipts`)

Hash the bytes, check `content_sha256` for an existing receipt, store the file
through `StorageService`, and persist the receipt row. Identical bytes are
rejected with 409 and the existing `receiptId`.

### Process (`POST /receipts/{id}/process`)

1. Load the receipt and its stored bytes.
2. Run OCR (stubbed - decodes the stored bytes as UTF-8) then parse into an
   immutable `ExtractionResult`.
3. Look up an existing transaction by `receipt_id`; build a new one or update in
   place.
4. Replace taxes and line items, compute `itemize_status`, and save - all in one
   database transaction.

### Re-itemize, patch, complete

`POST /transactions/{id}/itemize` re-runs auto-itemize from the stored OCR and
replaces line items only. `PATCH /transactions/{id}/items` applies a user
override but refuses it with 409 if it does not reconcile. `POST
/transactions/{id}/complete` confirms the itemization only if it reconciles.

## 5. Correctness guarantees

### 5.1 Receipt-to-transaction identity

`process` keys its lookup-or-create on `receipt_id`, so re-processing the same
receipt never produces a second transaction. Children are fully replaced each
run (`orphanRemoval = true`), so re-processing does not accumulate duplicate tax
or item rows. The sequential re-process test asserts both the stable transaction
id and the row counts.

### 5.2 Concurrency safety

Two overlapping `process` calls for the same receipt can both find "no existing
transaction" and both attempt an INSERT. The `UNIQUE (receipt_id)` constraint
lets exactly one INSERT succeed. The loser catches the
`DataIntegrityViolationException` in `TransactionService.upsertFromExtraction`,
re-reads the row the winner created, and applies its update to that same row.

The result is one transaction regardless of interleaving, enforced by the
database - no application lock, no Redis, no queue. This is optimistic: we let
the write race and resolve the conflict rather than serialising upfront, which
is simpler and provably correct for a single-row-per-receipt invariant. The
`concurrentProcessCreatesOnlyOneTransaction` test fires several threads at one
receipt and asserts exactly one transaction exists.

### 5.3 Atomicity

`ReceiptService.process` is `@Transactional`. In one transaction it marks the
receipt processed, stores the raw OCR text, and upserts the transaction header,
its taxes, and its line items. All of that commits together or rolls back
together - there is no window where taxes persist but items do not, or where a
receipt is flagged processed without its transaction. If extraction fails, the
transaction rolls back, the receipt stays unprocessed, and the
`receipts.process.failed` counter is incremented.

## 6. Reconciliation and status

`ReconciliationService` computes `sum(line_items) + sum(taxes)` at scale 2 and
compares it to `grand_total`:

- Reconciles and user confirms -> `COMPLETE`.
- Does not reconcile, or no items -> `NEEDS_REVIEW`.
- Extraction cannot produce a header -> request fails with 422 and no row is
  written.

`PATCH /items` recomputes against the stored taxes and `grand_total`; a
non-reconciling override returns 409 with the mismatch numbers and persists
nothing. `complete` applies the same check and refuses to force a
`NEEDS_REVIEW` transaction to `COMPLETE`. The service never adds a balancing
line and never rewrites `grand_total`.

### 6.1 Error model

The API uses HTTP status codes as a small, explicit contract. Each 4xx is a
deliberate decision, not a default:

| Status | When it happens | Endpoint(s) |
|--------|-----------------|-------------|
| `200 OK` | Read or state change succeeded | GET endpoints, process, itemize, patch (reconciling), complete |
| `201 Created` | New receipt stored | `POST /receipts` |
| `204 No Content` | Unprocessed receipt deleted | `DELETE /receipts/{id}` |
| `400 Bad Request` | Empty or unreadable upload | `POST /receipts` |
| `404 Not Found` | Receipt or transaction id does not exist | GET receipt / transaction / by `receipt_id` |
| `409 Conflict` | Duplicate file bytes (returns existing `receiptId`) | `POST /receipts` |
| `409 Conflict` | OCR requested before `process` has run | `GET /receipts/{id}/ocr` |
| `409 Conflict` | Items + taxes do not reconcile; nothing persisted | `PATCH /items`, `POST /complete` |
| `409 Conflict` | Receipt has a transaction, so delete is refused | `DELETE /receipts/{id}` |
| `422 Unprocessable Entity` | Extraction cannot produce a header; rolled back, no row | `POST /receipts/{id}/process` |

Conflict and reconciliation responses carry a structured payload (for example
the mismatch's `grandTotal`, `computedTotal`, `itemsTotal`, `taxesTotal`) so the
caller can see exactly why the request was rejected. The mapping lives in
`GlobalExceptionHandler`.

## 7. Money

All monetary values are `BigDecimal` at scale 2 with `HALF_UP` rounding
(`common.Money`); tax rate is scale 4. No IEEE float arithmetic is used, and
equality is compared by value (`compareTo`) so representation differences such
as `17.85` versus `17.8500` are treated as equal. `MoneyTest` pins these rules.

## 8. Storage abstraction

Callers depend only on the `StorageService` interface. Two adapters implement
it: `LocalStorageService` (filesystem, the default) and
`FlociiS3MultipartStorageService` (S3-compatible, using the S3 multipart upload
protocol with abort-on-failure). The active adapter is selected by
`storage.provider` via `@ConditionalOnProperty`, so adding a backend means
adding a class, not editing callers.

## 9. Observability

`ReceiptMetrics` registers Micrometer counters for the business events that
matter operationally: `receipts.uploaded`, `receipts.duplicate`,
`receipts.process.succeeded`, `receipts.process.failed`,
`transactions.reconcile.mismatch`, and `transactions.complete.rejected`. These
map directly to the questions an operator asks (dedupe rate, processing success
versus failure, how often itemization needs review). They are pushed to an
OpenTelemetry collector over OTLP and are also readable through Actuator at
`/actuator/metrics` without any backend.

## 10. Testing strategy

Tests run against a real on-disk H2 database so the Flyway schema and its
constraints are exercised, not mocked. Coverage is split between a parser unit
test (`ReceiptParserTest`), a money unit test (`MoneyTest`), and MockMvc
integration tests that hit the real HTTP API (`ReceiptApiIntegrationTest`),
including the nine scenarios the brief requires and a multi-threaded
concurrent-process race test. One command runs everything: `./mvnw test`.

## 11. Design decisions and tradeoffs

The brief left several rules to the implementer. The choices made here, and why:

- **Duplicate upload returns 409, not silent reuse.** Identical bytes are a
  client mistake worth surfacing. We return 409 with the existing `receiptId`
  so the caller can recover without guessing. The dedupe key is a database
  `UNIQUE` constraint on the SHA-256, not an application check that could race.
- **OCR-before-process returns 409, not 404.** The receipt exists, so 404 would
  mislead; 409 says "the resource is real but not yet in a state that has OCR."
- **Parse failure returns 422 and persists nothing.** The brief forbids
  inventing a placeholder header. Without a valid header there is nothing
  legitimate to store, so the request fails fast and rolls back. `FAILED` exists
  in the status enum as an explicit terminal state, but the current policy is
  fail-fast with 422; no code path writes a `FAILED` row, and that is
  intentional.
- **Reconciliation treats item amounts as net.** The gold fixtures carry net
  line amounts, so a transaction reconciles when
  `sum(items) + sum(taxes) == grand_total`. Empty items never reconcile, which
  is why the tax-only fixture is `NEEDS_REVIEW`.
- **Optimistic concurrency over locking.** For a one-row-per-receipt invariant,
  racing the INSERT and resolving the unique-constraint conflict is simpler than
  serialising, and avoids a lock service the brief rules out.
- **Timestamps are owned by the entity** (`@PrePersist`/`@PreUpdate`), so
  `created_at`/`updated_at` stay correct even if a future write path forgets to
  set them.
- **Feature-based packaging** keeps everything about one concept together and
  lets collaborators stay package-private, which scales better than grouping by
  technical layer.

## 12. Production and scale

The exercise is deliberately small, but the design has a clear path under load:

- **Read patterns and indexing.** The two hot lookups - dedupe by
  `content_sha256` and transaction-by-`receipt_id` - are both served by the
  `UNIQUE` indexes already in the schema, so they stay O(log n) as data grows.
  Reporting access patterns (by date or merchant) would add covering secondary
  indexes on `transactions(txn_date)` and `transactions(merchant)`; none are
  added today because no such query exists yet.
- **Horizontal scale.** The application is stateless; all state lives in the
  database and object store. Multiple instances can run behind a load balancer,
  and the `UNIQUE (receipt_id)` constraint keeps the one-transaction invariant
  correct no matter how many instances process the same receipt concurrently.
- **Retries and idempotency.** `process` is idempotent (create-or-update, no
  duplicate rows), so it is safe to retry from an upstream queue or a client
  with at-least-once delivery - the hallmark of a reliable integration.
- **Integration seams.** OCR sits behind `OcrService` and storage behind
  `StorageService`, so swapping the OCR stub for a real vendor, or the local
  store for Flocii S3, is a single-class change with no edits to callers.
- **Troubleshooting.** The business counters in Section 9 are the first stop for
  diagnosing a spike in failures, duplicates, or review-needed transactions in
  production.

What is intentionally out of scope (per the brief): auth, a UI, a real OCR
vendor, Redis/Kafka, distributed lock managers, and a reporting/analytics layer.
A correct, tested service on the fixtures is the goal; the notes above describe
the trajectory rather than code that exists today.
