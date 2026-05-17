#!/usr/bin/env bash
# 결제 멱등성 race 재현 — 같은 idempotency-key로 동시 N건 결제
# Usage: bash scripts/race-payment.sh <reservationId> <idempotencyKey> <concurrency>
# Example: bash scripts/race-payment.sh 1 idem-same-key 50

set -u

RES_ID="${1:?reservationId required}"
KEY="${2:?idempotencyKey required}"
CONCURRENCY="${3:-50}"
AMOUNT="${AMOUNT:-250000}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "Sending $CONCURRENCY concurrent payments with idempotency-key='$KEY' for reservation $RES_ID..."

seq 1 "$CONCURRENCY" | xargs -P "$CONCURRENCY" -I {} sh -c "
  curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST $BASE_URL/payments \
    -H 'X-User-Id: race-pay-user' \
    -H 'Idempotency-Key: $KEY' \
    -H 'Content-Type: application/json' \
    -d '{\"reservationId\": $RES_ID, \"amount\": $AMOUNT}'
" | sort | uniq -c

echo ""
echo "Verify in DB:"
echo "  docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \\"
echo "    \"SELECT count(*) FROM payment_attempt WHERE idempotency_key='$KEY'\""
echo ""
echo "  docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \\"
echo "    \"SELECT count(*) FROM payment WHERE reservation_id=$RES_ID\""
echo ""
echo "  Stage 1 naive: 둘 다 > 1 가능 (멱등성 깨짐)"
echo "  Stage 2 정상: 둘 다 = 1"
