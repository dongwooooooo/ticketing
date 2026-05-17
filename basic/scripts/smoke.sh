#!/usr/bin/env bash
# Happy path 자동 스모크 테스트
# Usage: bash scripts/smoke.sh
# 전제: 서버가 localhost:8080에 떠 있음

set -eu

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-smoke-$(date +%s)}"
SEAT_ID="${SEAT_ID:-1}"

echo "==> 1. Health check"
curl -s "$BASE_URL/actuator/health" | head -c 100; echo

echo ""
echo "==> 2. List schedules"
curl -s "$BASE_URL/events/1/schedules" | head -c 200; echo

echo ""
echo "==> 3. List VIP seats (section 1, first 5)"
curl -s "$BASE_URL/sections/1/seats?status=AVAILABLE&page=0&size=5" | head -c 500; echo

echo ""
echo "==> 4. Reserve seat $SEAT_ID for $USER_ID"
RES=$(curl -s -X POST "$BASE_URL/seats/$SEAT_ID/reservations" -H "X-User-Id: $USER_ID")
echo "$RES"
RES_ID=$(echo "$RES" | grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2)
echo "Reservation id: $RES_ID"

echo ""
echo "==> 5. Request payment"
IDEM_KEY="idem-smoke-$(date +%s%N)"
PAY=$(curl -s -X POST "$BASE_URL/payments" \
  -H "X-User-Id: $USER_ID" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"reservationId\": $RES_ID, \"amount\": 250000}")
echo "$PAY"

echo ""
echo "==> 6. Wait 2s for PG mock callback"
sleep 2

echo ""
echo "==> 7. Final reservation state"
curl -s "$BASE_URL/reservations/$RES_ID"; echo

echo ""
echo "==> 8. Seat status in DB"
docker exec ticketing_basic_db psql -U ticketing -d ticketing -t -c \
  "SELECT id, status FROM seat WHERE id=$SEAT_ID" 2>/dev/null || echo "(psql 미사용 환경)"

echo ""
echo "==> done"
