## Product context

A user uploads a **receipt**. The system:

1. Stores the file and creates **at most one transaction** from that receipt.
2. Parses and **stores taxes** as their own records (not a single tax number on the header).
3. **Auto-itemizes**: splits the receipt into several **line items**, the way expense auto-itemize works today (the
   model proposes items from the receipt; the user can still edit them).

If line items do not add up to the receipt total, keep the transaction and mark itemization as needing review. Do **not
** invent a fake line to force the math to work.

Money is real: two overlapping processes must not double-count a receipt.

---

## What to build

A small HTTP API backed by a **real database**. **SQLite** (a local `.db` file) is enough. Postgres, MySQL, or
file-backed H2/SQL Server are also fine.

**Do not** store receipts, transactions, taxes, or line items in process memory (`dict`, `HashMap`, a list, or an
in-memory-only engine such as `jdbc:h2:mem:`). Uniqueness (`receipt_id` → one transaction) must be a **database
constraint**, not only application code.

No login, no UI, no PDF renderer.

### Endpoints

| Method   | Path                            | Behavior                                                                                                                                                                                                                                                                                                                   |
|----------|---------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST`   | `/receipts`                     | Multipart upload of an image or PDF (fixture `.txt` as a stand-in is fine if documented). Store the file. Return a `receipt_id`. Identical file bytes should not create a second receipt that can be processed into a second transaction (return the existing `receipt_id`, or **409** — pick one rule and document it).   |
| `GET`    | `/receipts/{id}`                | Return the stored receipt (id, original filename, uploaded time, whether it has been processed, `transaction_id` if any). **404** if missing. Do not require the caller to guess ids.                                                                                                                                      |
| `GET`    | `/receipts/{id}/ocr`            | Return **stored** raw OCR text. **404** if the receipt is missing; **409** (or 404) if `process` has not run yet — pick one and document it.                                                                                                                                                                               |
| `POST`   | `/receipts/{id}/process`        | Run OCR + extraction. **Create or update** the **one** transaction for this receipt. Never a second row for the same `receipt_id`, including **concurrent** calls. Persist **tax lines**, **auto-itemize line items**, and **raw OCR text**. The write of header + taxes + items must be **atomic** (all persist or none). |
| `DELETE` | `/receipts/{id}`                | Delete an **unprocessed** receipt (file + row). If a transaction already exists for this receipt, return **409** and delete nothing (do not orphan or silently drop money).                                                                                                                                                |
| `GET`    | `/transactions/{id}`            | Return the transaction with its taxes and line items, plus `itemize_status`. Include a `receipt_id`.                                                                                                                                                                                                                       |
| `GET`    | `/transactions?receipt_id={id}` | Return the **one** transaction for that receipt, or **404**. Must not return a list of duplicates.                                                                                                                                                                                                                         |
| `POST`   | `/transactions/{id}/itemize`    | Re-run auto-itemize from **stored OCR** (not a re-upload). Replace **line items only**. Do not create a second transaction. Do not rewrite merchant/date/currency/grand total/tax rows.                                                                                                                                    |
| `PATCH`  | `/transactions/{id}/items`      | User override: edit / merge / split items. If amounts no longer reconcile with the transaction total **and stored taxes**, return **409** with the mismatch payload. Do not persist the patch. Do not silently change `grand_total`.                                                                                       |
| `POST`   | `/transactions/{id}/complete`   | User confirms itemization. Set `itemize_status` to `COMPLETE` **only if** line items + stored taxes reconcile with `grand_total`. Otherwise **409** and do not change status (a `NEEDS_REVIEW` mismatch must not be forced complete).                                                                                      |

`GET /health` is welcome.

You may use a real OCR/VLM API, or treat fixture files as already-known images and extract from provided OCR text in
`/fixtures`. If you stub OCR, say so in the README. Stubbing is a complete solution for this exercise.

### Identity, concurrency, and money (required)

**One receipt → one transaction.** `POST /receipts/{id}/process` must create or update that receipt’s transaction.
Calling it again must not insert a second transaction.

**Concurrent `process`:** two overlapping requests for the same `receipt_id` must not create two transactions. Put a *
*unique constraint** on the transaction’s `receipt_id` (or equivalent) and handle the conflict. A transactional upsert
is fine. `id = len(map) + 1` is not. Do not add Redis, Kafka, or a lock service.

**Atomicity:** extracting then writing taxes in one request and items in a later crash-window is a bug. One process
call = one commit of the structured result.

**Parse failure:** if merchant, date, currency, or total cannot be extracted, return **4xx**. Do not invent
`"Unknown Merchant"` / `1970-01-01` / `0.00`.

**Money:** use a decimal type or integer minor units. Document the scale. Do not treat IEEE `float` addition as good
enough.

### Data we expect to exist after `process`

**Transaction (header)**  
merchant/supplier, date, currency, grand total, link to `receipt_id`.

**Taxes (list)**  
Each row: `name` (e.g. VAT, GST), `rate`, `amount`. Optional jurisdiction if you have time.

**Line items (auto-itemize)**  
Each row: `description`, `amount`, optional `tax_amount` / quantity.

**Itemize status**  
`COMPLETE` | `NEEDS_REVIEW` | `FAILED` (or equivalent names).

**Raw OCR**  
Stored and used as the source for re-itemize.

---

## Fixtures

Use the files in `fixtures/task-a/` (shipped with this brief):

| File                   | What it tests                     |
|------------------------|-----------------------------------|
| `receipt-clean.txt`    | Happy path: items + VAT           |
| `receipt-tax-only.txt` | Tax present, no useful line items |
| `receipt-mismatch.txt` | Line items do not sum to total    |

`gold.json` is the expected shape of extraction for those three. You do not need a pixel-perfect OCR engine; matching
the gold fields on fixtures is enough.

---

## Tests (required)

Automated tests are **required**. README must include **one command** that runs them (for example `./mvnw test`,
`./gradlew test`, `pytest`).

Minimum coverage — we will fail the submission if these are missing:

1. **Clean fixture** matches `gold.json` fields after `process` (`COMPLETE`, taxes, line items, total).
2. **Mismatch fixture** keeps the printed `grand_total`, does **not** add a balancing line, `itemize_status` is
   `NEEDS_REVIEW`.
3. **Tax-only fixture** is `NEEDS_REVIEW` (empty items, or a documented fallback that still needs review).
4. **PATCH 409** when items + stored taxes do not reconcile; persisted items and `grand_total` unchanged.
5. **Re-process** the same `receipt_id` twice, sequentially: still **one** transaction id; no accumulated duplicate
   tax/item rows.
6. **Concurrent `process`** on the same `receipt_id` (two overlapping calls): still **one** transaction. A
   unique-constraint conflict test, a lock test, or an equivalent race test is acceptable. A comment in README is not.
7. **GET `/receipts/{id}`** and **GET `/transactions?receipt_id=`** after process return the same transaction id.
8. **DELETE** of a processed receipt returns **409** and the transaction is still there.
9. **POST `/complete`** on the mismatch fixture returns **409** and status stays `NEEDS_REVIEW`.

You choose the test framework. Hitting the real HTTP API (MockMvc, TestClient, etc.) is preferred over only unit-testing
the regex.

---

## Out of scope

Auth, UI, policy/compliance, ledgers, invoices, inventory, flights/hotels/rail, production object storage, Redis/Kafka,
distributed lock managers, Prometheus/nginx.

A unique **database** constraint on receipt→transaction, atomic writes, and the test list above **are in scope.**

---

## Submit

1. Git repo URL + commit SHA.
2. `README.md`: how to **run the API** in one command, how to **run tests** in one command, plus example curls for *
   *every** endpoint (including GET receipt, GET OCR, GET by `receipt_id`, DELETE 409, POST complete, and **process
   twice** on the same id).
3. Note which model/OCR vendor you used, **or that OCR is stubbed** (stub is fine).
4. `ARCHITECTURE.md` (one page is enough): receipt→transaction identity, how concurrent `process` is safe, how process
   is atomic.

A correct, tested service on the three fixtures beats an unfinished “platform.”