# 로컬 실행 + 테스트 명령어 모음

복사-붙여넣기로 바로 돌릴 수 있게 정리. 터미널 3개 띄워서 (1) PG (2) 서버 (3) curl 테스트.

## 0. 사전 확인

```bash
# Docker 실행 중?
docker ps | head -1

# Java 25 사용 가능?
/usr/libexec/java_home -v 25
# /Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home 출력되면 OK
```

Java 25 없으면 Corretto 25 설치 또는 `sdk install java 25-amzn` (sdkman).

## 1. 인프라 기동 (PostgreSQL)

터미널 1에서:

```bash
cd /Users/idong-u/d/ticketing/basic
docker-compose up -d

# 헬스 확인
docker-compose ps
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c '\dt'
# (Flyway 마이그레이션은 서버 부팅 시 실행되므로 이 시점엔 테이블 없음)
```

## 2. 서버 실행

터미널 2에서:

```bash
cd /Users/idong-u/d/ticketing
./gradlew :basic:bootRun

# 부팅 후 확인 (다른 터미널에서):
# - Flyway: Migrating schema "public" to version "1 - init"
# - Flyway: Migrating schema "public" to version "2 - seed event"
# - Tomcat started on port 8080
# - Started TicketingBasicApplication
```

서버 헬스 확인:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

좌석 50,000 seed 확인:

```bash
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "SELECT s.name, count(*) FROM seat se JOIN section s ON s.id=se.section_id GROUP BY s.name ORDER BY s.id"
# VIP      | 2000
# R        | 8000
# S        | 15000
# A        | 15000
# STANDING | 10000
```

## 3. Happy Path 스모크 테스트

터미널 3에서:

```bash
# 1) 회차 조회
curl -s http://localhost:8080/events/1/schedules | jq .

# 2) 구역 조회
curl -s http://localhost:8080/events/1/schedules/1/sections | jq .

# 3) VIP 구역(id=1) 첫 100개 좌석
curl -s 'http://localhost:8080/sections/1/seats?status=AVAILABLE&page=0&size=100' | jq '.content | length'
# 100

# 4) 좌석 1 예매
curl -s -X POST http://localhost:8080/seats/1/reservations \
  -H "X-User-Id: user-alice" | jq .
# {
#   "id": 1,
#   "seatId": 1,
#   "userId": "user-alice",
#   "status": "HELD",
#   "expiresAt": "..."
# }

# 5) 결제 요청 (Idempotency-Key 필수)
curl -s -X POST http://localhost:8080/payments \
  -H "X-User-Id: user-alice" \
  -H "Idempotency-Key: idem-$(date +%s%N)" \
  -H "Content-Type: application/json" \
  -d '{"reservationId": 1, "amount": 250000, "method": "CARD"}' | jq .
# {
#   "id": 1,
#   "reservationId": 1,
#   "amount": 250000,
#   "status": "REQUESTED"
# }

# 6) 1초 대기 후 (PG mock 자동 callback)
sleep 2

# 7) 예약 상태 확인
curl -s http://localhost:8080/reservations/1 | jq .
# "status": "PAID"

# 8) 좌석 상태 확인
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "SELECT id, status FROM seat WHERE id=1"
# 1 | SOLD
```

## 4. 자가 취소 분기

```bash
# 좌석 5 예매
curl -s -X POST http://localhost:8080/seats/5/reservations \
  -H "X-User-Id: user-bob" | jq .
# id=2 가정

# 결제 안 하고 즉시 취소
curl -i -X DELETE http://localhost:8080/reservations/2 \
  -H "X-User-Id: user-bob"
# HTTP/1.1 204 No Content

# 좌석 상태 복귀 확인
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "SELECT id, status FROM seat WHERE id=5"
# 5 | AVAILABLE
```

## 5. 만료 분기 (5초 만료 모드)

기본 TTL은 5분. 빠른 테스트 위해 서버 종료 후 환경변수로 짧게:

```bash
# 터미널 2 서버 종료 (Ctrl+C)

# Reservation 클래스의 HOLD_DURATION을 5초로 줄이려면 코드 수정 필요
# 또는 시드 데이터로 expires_at을 과거로 INSERT:
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "INSERT INTO reservation (seat_id, user_id, status, expires_at) VALUES (10, 'user-expired', 'HELD', now() - interval '1 minute')"

# 좌석 10도 HELD로 변경
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "UPDATE seat SET status='HELD' WHERE id=10"

# 서버 재기동 후 5초 안에 ExpiryService가 발화
./gradlew :basic:bootRun &
sleep 10

# 만료 확인
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "SELECT id, status FROM reservation WHERE seat_id=10"
# EXPIRED

docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "SELECT id, status FROM seat WHERE id=10"
# AVAILABLE
```

## 6. Race 의도적 재현 — 좌석 동시 100건 예매

`scripts/race-reserve.sh` 사용:

```bash
cd /Users/idong-u/d/ticketing/basic
bash scripts/race-reserve.sh 1 100
# 좌석 1에 동시 100건 예매 시도

# 결과 확인
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "SELECT count(*) as held_count FROM reservation WHERE seat_id=1 AND status='HELD'"
# Stage 1 naive: held_count > 1 가능 (oversell 재현)
# Stage 2 정상 구현: held_count = 1
```

## 7. Race 의도적 재현 — 결제 멱등성 (같은 key 동시 N건)

```bash
# 새 좌석 예매 먼저
RES_ID=$(curl -s -X POST http://localhost:8080/seats/2000/reservations \
  -H "X-User-Id: user-idem" | jq -r .id)
echo "reservation: $RES_ID"

# 같은 idempotency-key로 동시 50건 결제 요청
bash scripts/race-payment.sh $RES_ID idem-same-key-X 50

# 결과 확인
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c \
  "SELECT count(*) FROM payment_attempt WHERE idempotency_key='idem-same-key-X'"
# Stage 1 naive: > 1 (멱등성 깨짐)
# Stage 2 정상 구현: = 1
```

## 8. Race 의도적 재현 — PG callback 중복

`application.yml` 또는 환경변수로 PG mock 중복 발사 활성화:

```bash
# 서버 종료 후 재기동
TICKETING_PGMOCK_DUPLICATE_CALLBACKS=10 ./gradlew :basic:bootRun &
sleep 5

# 예매 + 결제
RES=$(curl -s -X POST http://localhost:8080/seats/3000/reservations \
  -H "X-User-Id: user-dup-cb" | jq -r .id)
curl -s -X POST http://localhost:8080/payments \
  -H "X-User-Id: user-dup-cb" \
  -H "Idempotency-Key: idem-dup-$(date +%s%N)" \
  -H "Content-Type: application/json" \
  -d "{\"reservationId\": $RES, \"amount\": 130000, \"method\": \"CARD\"}"

sleep 3

# PG mock이 같은 callback을 11회 발사 (1차 + duplicate 10)
# Stage 1 naive: payment.status 11번 변경됨 (log로 확인)
# 서버 콘솔에서 "Handling callback for paymentId=X" 11번 출력 확인
```

## 9. 결과 일관성 검증 쿼리

```bash
# 좌석-예약 정합성 (Stage 1 위반 가능)
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c "
SELECT
  s.id seat_id,
  s.status seat_status,
  count(r.id) FILTER (WHERE r.status IN ('HELD','PAID')) active_reservations
FROM seat s
LEFT JOIN reservation r ON r.seat_id = s.id
WHERE s.status != 'AVAILABLE'
GROUP BY s.id, s.status
HAVING count(r.id) FILTER (WHERE r.status IN ('HELD','PAID')) > 1
LIMIT 10
"
# Stage 1: oversell 좌석이 나올 수 있음
# Stage 2: 0건 결과

# 결제 멱등성 위반
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c "
SELECT idempotency_key, count(*) FROM payment_attempt
GROUP BY idempotency_key HAVING count(*) > 1
LIMIT 10
"

# 좌석 구역별 상태 분포
docker exec ticketing_basic_db psql -U ticketing -d ticketing -c "
SELECT s.name, se.status, count(*)
FROM seat se JOIN section s ON s.id = se.section_id
GROUP BY s.name, se.status
ORDER BY s.id, se.status
"
```

## 10. DB 리셋

상태를 다시 초기화하고 싶을 때:

```bash
docker-compose down -v
docker-compose up -d

# 서버 재기동하면 Flyway가 V1 + V2 다시 실행
./gradlew :basic:bootRun
```

## 11. 정리

```bash
# 서버 종료: Ctrl+C
docker-compose down

# 볼륨까지 삭제하려면
docker-compose down -v
```

## 12. 자주 보는 로그

```bash
# Hibernate SQL 로그 (application.yml에서 enable됨)
# 서버 콘솔에서 모든 SELECT/INSERT/UPDATE가 보임

# PG mock callback 로그
# "Callback send failed" 가 보이면 callback URL 잘못 설정

# Flyway 마이그레이션
# "Schema history table [...] does not exist" → 첫 부팅 정상
# "Successfully validated 2 migrations" → OK
```

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| `Connection refused: localhost:5432` | PG 안 떴음 | `docker-compose up -d` 후 5초 대기 |
| `bootRun` 부팅 실패: `Could not find a valid Docker environment` | Testcontainers 인식 실패 (test 한정) | 본 가이드는 실서버 기동이라 무관, 무시 |
| `port 8080 already in use` | 다른 Spring app | `lsof -i:8080` 확인 후 kill |
| `Java 25 not found` | toolchain | Corretto 25 설치 또는 `~/.gradle/gradle.properties`에 toolchain path |
| 좌석 1 예매 후 다시 예매하면 409 | 정상 동작 | 다른 seatId 사용 (2~50000) |
