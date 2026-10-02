#!/usr/bin/env bash
# End-to-end curl workflow exercising every endpoint.
# Usage: scripts/demo.sh [BASE_URL] [FIXTURES_DIR]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
FIXTURES="${2:-task-a-receipt-autoitemize/fixtures/task-a}"

say() { printf "\n=== %s ===\n" "$1"; }

say "Health"
curl -s "${BASE_URL}/health"; echo

say "Upload receipt-clean.txt (POST /receipts)"
RECEIPT_ID=$(curl -s -F "file=@${FIXTURES}/receipt-clean.txt" "${BASE_URL}/receipts" | tee /dev/stderr | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
echo "receipt_id=${RECEIPT_ID}"

say "Duplicate upload returns 409 (POST /receipts, same bytes)"
curl -s -o /dev/stderr -w "HTTP %{http_code}\n" -F "file=@${FIXTURES}/receipt-clean.txt" "${BASE_URL}/receipts"

say "GET /receipts/{id}"
curl -s "${BASE_URL}/receipts/${RECEIPT_ID}"; echo

say "GET /receipts/{id}/ocr BEFORE process (expect 409)"
curl -s -o /dev/stderr -w "HTTP %{http_code}\n" "${BASE_URL}/receipts/${RECEIPT_ID}/ocr"

say "Process (POST /receipts/{id}/process)"
TXN_ID=$(curl -s -X POST "${BASE_URL}/receipts/${RECEIPT_ID}/process" | tee /dev/stderr | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
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
MIS_ID=$(curl -s -F "file=@${FIXTURES}/receipt-mismatch.txt" "${BASE_URL}/receipts" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
MIS_TXN=$(curl -s -X POST "${BASE_URL}/receipts/${MIS_ID}/process" | tee /dev/stderr | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
echo
curl -s -o /dev/stderr -w "complete HTTP %{http_code}\n" -X POST "${BASE_URL}/transactions/${MIS_TXN}/complete"

say "Published custom metrics via Actuator"
curl -s "${BASE_URL}/actuator/metrics/receipts.uploaded"; echo

echo
echo "Workflow complete."
