#!/usr/bin/env bash
# 좌석 동시 예매 race 재현
# Usage: bash scripts/race-reserve.sh <seatId> <concurrency>
# Example: bash scripts/race-reserve.sh 1 100

set -u

SEAT_ID="${1:-1}"
CONCURRENCY="${2:-100}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "Firing $CONCURRENCY concurrent reservations for seat $SEAT_ID..."

# xargs로 N개 병렬 발사
seq 1 "$CONCURRENCY" | xargs -P "$CONCURRENCY" -I {} sh -c "
  curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST $BASE_URL/seats/$SEAT_ID/reservations \
    -H 'X-User-Id: race-user-{}'
" | sort | uniq -c

echo ""
echo "Result legend: <count> <http_status>"
echo "  201 = reservation created (Stage 1 naive: oversell 발생 가능)"
echo "  409 / 500 = rejected"
echo ""
echo "Verify in DB:"
echo "  docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \\"
echo "    \"SELECT count(*) FROM reservation WHERE seat_id=$SEAT_ID AND status='HELD'\""
