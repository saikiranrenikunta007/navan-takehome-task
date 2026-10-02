#!/usr/bin/env bash
# End-to-end curl workflow exercising every endpoint.
# Usage: scripts/demo.sh [BASE_URL] [FIXTURES_DIR]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
FIXTURES="${2:-task-a-receipt-autoitemize/fixtures/task-a}"

say() { printf "\n=== %s ===\n" "$1"; }

# Extract the FIRST "id" from a JSON body. The transaction/receipt id is always
# the first id in the response; nested taxes/line_items also carry "id", so a
# greedy match would wrongly grab the last one. Never fails the pipeline (returns
# empty on no match) so `set -e`/`pipefail` stay happy on, e.g., a 409 body.
first_id() {
  local body
  body="$(cat)"
  printf '%s' "${body}" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*' || true
}

say "Health"
curl -s "${BASE_URL}/health"; echo

say "Upload receipt-clean.txt (POST /receipts)"
RECEIPT_ID=$(curl -s -F "file=@${FIXTURES}/receipt-clean.txt" "${BASE_URL}/receipts" | tee /dev/stderr | first_id)
echo "receipt_id=${RECEIPT_ID}"

say "Duplicate upload returns 409 (POST /receipts, same bytes)"
curl -s -o /dev/stderr -w "HTTP %{http_code}\n" -F "file=@${FIXTURES}/receipt-clean.txt" "${BASE_URL}/receipts"

say "GET /receipts/{id}"
curl -s "${BASE_URL}/receipts/${RECEIPT_ID}"; echo

say "GET /receipts/{id}/ocr BEFORE process (expect 409)"
curl -s -o /dev/stderr -w "HTTP %{http_code}\n" "${BASE_URL}/receipts/${RECEIPT_ID}/ocr"

say "Process (POST /receipts/{id}/process)"
TXN_ID=$(curl -s -X POST "${BASE_URL}/receipts/${RECEIPT_ID}/process" | tee /dev/stderr | first_id)
echo "transaction_id=${TXN_ID}"

say "Process AGAIN on the same receipt (still one transaction, no duplicates)"
curl -s -X POST "${BASE_URL}/receipts/${RECEIPT_ID}/process"; echo

say "GET /receipts/{id}/ocr AFTER process"
curl -s "${BASE_URL}/receipts/${RECEIPT_ID}/ocr"; echo

say "GET /transactions/{id}"
curl -s "${BASE_URL}/transactions/${TXN_ID}"; echo

say "GET /transactions?receipt_id={id}"
curl -s "${BASE_URL}/transactions?receipt_id=${RECEIPT_ID}"; echo

say "Re-itemize from stored OCR (POST /transactions/{id}/itemize)"
curl -s -X POST "${BASE_URL}/transactions/${TXN_ID}/itemize"; echo

say "PATCH items with a non-reconciling set (expect 409)"
curl -s -o /dev/stderr -w "HTTP %{http_code}\n" -X PATCH "${BASE_URL}/transactions/${TXN_ID}/items" \
  -H 'Content-Type: application/json' \
  -d '{"items":[{"description":"Merged","amount":1.00}]}'

say "Complete (clean fixture reconciles -> COMPLETE)"
curl -s -X POST "${BASE_URL}/transactions/${TXN_ID}/complete"; echo

say "DELETE processed receipt (expect 409, transaction preserved)"
curl -s -o /dev/stderr -w "HTTP %{http_code}\n" -X DELETE "${BASE_URL}/receipts/${RECEIPT_ID}"

say "Mismatch fixture: process then complete (expect 409, stays NEEDS_REVIEW)"
MIS_ID=$(curl -s -F "file=@${FIXTURES}/receipt-mismatch.txt" "${BASE_URL}/receipts" | first_id)
MIS_TXN=$(curl -s -X POST "${BASE_URL}/receipts/${MIS_ID}/process" | tee /dev/stderr | first_id)
echo
curl -s -o /dev/stderr -w "complete HTTP %{http_code}\n" -X POST "${BASE_URL}/transactions/${MIS_TXN}/complete"

say "Parse failure: upload an unextractable receipt, process returns 422 (no transaction, no placeholders)"
BAD_FILE="$(mktemp -t receipt-unparseable.XXXXXX.txt)"
printf 'scanned noise with no recognizable header fields\n' > "${BAD_FILE}"
BAD_ID=$(curl -s -F "file=@${BAD_FILE}" "${BASE_URL}/receipts" | first_id)
curl -s -o /dev/stderr -w "process HTTP %{http_code}\n" -X POST "${BASE_URL}/receipts/${BAD_ID}/process"
rm -f "${BAD_FILE}"

say "Published custom metrics via Actuator"
curl -s "${BASE_URL}/actuator/metrics/receipts.uploaded"; echo

echo
echo "Workflow complete."
