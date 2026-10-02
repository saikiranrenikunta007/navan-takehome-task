# Receipt upload, taxes, and auto-itemize API

A small Spring Boot service that stores an uploaded receipt, runs OCR plus
extraction, and creates exactly one transaction per receipt with its taxes and
auto-itemized line items. If the items do not add up to the printed total, the
transaction is kept and marked `NEEDS_REVIEW`. No balancing line is ever
invented.

- Language: Java 21, Spring Boot 3
- Database: MySQL 8 (Docker). Tests run on a real on-disk H2 file, never
  `jdbc:h2:mem`.
- Storage: a `StorageService` abstraction with two adapters - local filesystem
  (default) and Flocii (S3-compatible) using S3 multipart upload.
- Observability: custom business metrics via Micrometer, exported over OTLP to
  an OpenTelemetry collector and also visible through Actuator.
- API docs: OpenAPI / Swagger UI.

## OCR is stubbed

There is no external OCR/VLM vendor call. The fixtures are plain-text
stand-ins for scanned receipts, so the "OCR" step (`OcrService`) just decodes
the stored bytes as UTF-8. Swapping in a real vendor means replacing that one
class. Extraction (`ReceiptParser`) matches the `gold.json` fields on the three
fixtures.

## Prerequisites

You need **Docker** (to run the service) and, only if you want to run the tests
or the app outside Docker, **Java 21**. The Maven wrapper (`./mvnw`) is bundled,
so Maven itself is not required. `make` is optional - every `make` target shows
the raw command it runs, so you can run that command directly if `make` is
absent.

Minimum to run everything in this README: Docker, plus Java 21 for `./mvnw test`.

**macOS (Homebrew):**

```bash
# One-shot: installs Java 21 and Docker (requires Homebrew: https://brew.sh)
make setup
# Equivalent without make:
brew install openjdk@21
brew install --cask docker
```

**Ubuntu / Debian (apt):**

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk make
# Docker Engine + Compose plugin:
sudo apt-get install -y docker.io docker-compose-plugin
sudo systemctl enable --now docker
```

**Fedora / RHEL (dnf):**

```bash
sudo dnf install -y java-21-openjdk make docker docker-compose-plugin
sudo systemctl enable --now docker
```

Verify the toolchain before running:

```bash
docker --version          # Docker present
docker compose version    # Compose v2 plugin present
java -version             # should report 21 (only needed for the non-Docker path)
```

Start Docker Desktop (macOS) or the Docker daemon (Linux) before `make up`.

## Run the API (one command)

Starts MySQL, the OTel collector, and the app:

```bash
make up        # docker compose up --build -d
```

If you do not have `make`, run the underlying command directly:

```bash
docker compose up --build -d
```

The API is then on `http://localhost:8080`. Swagger UI:
`http://localhost:8080/swagger-ui.html`.

Stop everything:

```bash
make down      # or: docker compose down
```

To run the app directly against a local MySQL instead of in Docker, start the
database first (`docker compose up -d mysql`) then `make run`.

## Run the tests (one command)

```bash
./mvnw test
```

This runs the parser unit tests and the MockMvc integration tests (including
the concurrent-process race test) against a file-backed H2 database.

## Money and scale

Money is stored as `DECIMAL(19,2)` and handled as `BigDecimal` at scale 2 with
`HALF_UP` rounding. No IEEE float arithmetic is used. Tax `rate` is stored as a
fraction (for example `0.19`) at scale 4.

## Decisions you were asked to pick

- Duplicate upload: identical file bytes return **409 Conflict** with the
  existing `receiptId` in the body. Dedupe key is the SHA-256 of the bytes, with
  a `UNIQUE` constraint in the database.
- OCR before process: `GET /receipts/{id}/ocr` returns **409** if `process` has
  not run yet.
- Reconciliation: a transaction reconciles when
  `sum(line_items) + sum(taxes) == grand_total` at scale 2. Items in the gold
  file are net amounts, so this holds for the clean fixture
  (`3.50 + 8.90 + 2.60 + 2.85 VAT = 17.85`).

## Endpoints and example curls

Set a base URL and upload a fixture:

```bash
BASE=http://localhost:8080

# POST /receipts - multipart upload (returns receipt_id). Duplicate bytes -> 409.
curl -F "file=@task-a-receipt-autoitemize/fixtures/task-a/receipt-clean.txt" $BASE/receipts

# Duplicate upload of the same file -> 409 with the existing receiptId
curl -i -F "file=@task-a-receipt-autoitemize/fixtures/task-a/receipt-clean.txt" $BASE/receipts

# GET /receipts/{id}
curl $BASE/receipts/1

# GET /receipts/{id}/ocr - 409 before process, stored OCR text after
curl -i $BASE/receipts/1/ocr

# POST /receipts/{id}/process - create or update the one transaction (atomic)
curl -X POST $BASE/receipts/1/process

# Process again on the same receipt - still one transaction, no duplicate rows
curl -X POST $BASE/receipts/1/process

# GET /transactions/{id}
curl $BASE/transactions/1

# GET /transactions?receipt_id={id} - the single transaction for a receipt
curl "$BASE/transactions?receipt_id=1"

# POST /transactions/{id}/itemize - re-run auto-itemize from stored OCR
curl -X POST $BASE/transactions/1/itemize

# PATCH /transactions/{id}/items - override items; 409 if they do not reconcile
curl -i -X PATCH $BASE/transactions/1/items \
  -H 'Content-Type: application/json' \
  -d '{"items":[{"description":"Merged","amount":1.00}]}'

# POST /transactions/{id}/complete - 409 unless items + taxes reconcile
curl -X POST $BASE/transactions/1/complete

# DELETE /receipts/{id} - 409 if a transaction exists; deletes nothing
curl -i -X DELETE $BASE/receipts/1

# GET /health
curl $BASE/health
```

A full scripted walkthrough of every endpoint (including the mismatch fixture
returning 409 on complete) is in `scripts/demo.sh`:

```bash
make demo   # runs scripts/demo.sh against http://localhost:8080
```

## Observability

Custom counters are registered on the Micrometer registry:

- `receipts.uploaded`, `receipts.duplicate`
- `receipts.process.succeeded`, `receipts.process.failed`
- `transactions.reconcile.mismatch`, `transactions.complete.rejected`

They are pushed to the OTel collector over OTLP (see the collector logs with
`docker compose logs -f otel-collector`) and are also readable via Actuator:

```bash
curl $BASE/actuator/metrics/receipts.uploaded
curl $BASE/actuator/metrics
```

## Using Flocii (S3 multipart) instead of local storage

Local filesystem storage is the default, so no configuration is needed to run or
test the service. Set the provider to `s3` only when you want to use Flocii.

Set the storage provider to `s3` and provide credentials via environment
variables (never commit them):

```bash
export STORAGE_PROVIDER=s3
export FLOCII_S3_ENDPOINT=https://<your-flocii-s3-endpoint>
export FLOCII_S3_BUCKET=<bucket>
export FLOCII_S3_ACCESS_KEY=YOUR_KEY
export FLOCII_S3_SECRET_KEY=YOUR_SECRET
```

The `FlociiS3MultipartStorageService` uploads bytes using the S3 multipart
protocol (create, upload parts, complete, abort on failure).

## Project layout (feature-based)

```
com.navan.task
  common         exceptions, Money, ItemizeStatus, health, OpenAPI config
  storage        StorageService + local and Flocii S3 adapters
  extraction     OCR stub, parser, extraction result
  receipt        Receipt entity, repository, service, controller
  transaction    Transaction/Tax/LineItem, reconciliation, service, controller
  observability  custom metrics
```

See `ARCHITECTURE.md` for identity, concurrency, and atomicity details.
