# API 명세

Stage 1 (basic) REST API.

| 메서드 | 경로 | 인증 | 동시성 위험도 | 설명 |
|---|---|---|---|---|
| GET | /events/{eventId}/schedules | X | LOW | 회차 목록 |
| GET | /events/{eventId}/schedules/{scheduleId}/sections | X | LOW | 구역 목록 |
| GET | /sections/{sectionId}/seats?status=AVAILABLE&page=0&size=100 | X | LOW | 좌석 목록 (필터+페이징) |
| POST | /seats/{seatId}/reservations | `X-User-Id` 필수 | **HIGH** | 좌석 선점 (HELD 5분 TTL) — Stage 2 Deep Dive 1 |
| GET | /reservations/{reservationId} | X | LOW | 예약 상태 조회 |
| DELETE | /reservations/{reservationId} | `X-User-Id` 필수 | MEDIUM | 자가 취소 (HELD 상태만) |
| POST | /payments | `X-User-Id`, `Idempotency-Key` 필수 | **HIGH** | 결제 요청 (PG mock 비동기 callback) — Stage 2 Deep Dive 2 |
| POST | /payments/callback | (PG mock 내부 호출) | **HIGH** | PG 콜백 처리 — Stage 2 Deep Dive 2/3 |
| GET | /actuator/health | X | - | 헬스체크 |

## 1. 좌석 선점 — POST /seats/{seatId}/reservations

### Request

```http
POST /seats/100/reservations HTTP/1.1
X-User-Id: user-123
```

### Response 201 Created

```json
{
  "id": 42,
  "seatId": 100,
  "userId": "user-123",
  "status": "HELD",
  "expiresAt": "2026-05-17T16:30:00"
}
```

### Errors

- 400 `seat not found` — seatId 없음
- 409 `seat not available` — seat.status가 AVAILABLE 아님

### 동시성 위험

Stage 1: 같은 seatId에 동시 N건 → N건 모두 성공 가능 (oversell). [docs/flow.md §6.1](flow.md#61-좌석-선점-race-i-001) 참고.

## 2. 결제 요청 — POST /payments

### Request

```http
POST /payments HTTP/1.1
X-User-Id: user-123
Idempotency-Key: idem-abc-123
Content-Type: application/json

{
  "reservationId": 42,
  "amount": 250000,
  "method": "CARD"
}
```

### Response 202 Accepted

```json
{
  "id": 99,
  "reservationId": 42,
  "amount": 250000,
  "status": "REQUESTED"
}
```

1초 후 `POST /payments/callback`이 자동으로 발사된다 (PG mock).

### Errors

- 400 `reservation not HELD` — 예약 상태가 HELD 아님
- 400 `reservation not found`

### 동시성 위험

Stage 1: 같은 Idempotency-Key 동시 N건 → N건 모두 성공 가능 (중복 결제). [docs/flow.md §6.2](flow.md#62-결제-멱등성-race-i-002).

## 3. PG Callback — POST /payments/callback

내부 PG mock → 우리 서버. 실제 운영에서는 PG가 호출.

### Request

```json
{
  "paymentId": 99,
  "result": "SUCCESS"
}
```

`result`: `"SUCCESS"` 또는 `"FAIL"`.

### Response 200 OK

빈 body.

### Effects (SUCCESS)

1. payment.status = CONFIRMED, approved_at = now()
2. reservation.status = PAID
3. seat.status = SOLD

### Effects (FAIL)

1. payment.status = FAILED
2. reservation.status = CANCELLED
3. seat.status = AVAILABLE

### 동시성 위험

Stage 1: 중복 callback N회 → N번 상태 변경 시도 + 만료 처리와 race 시 lost update. [docs/flow.md §6.3, §6.4](flow.md#63-결제-callback-중복).

## 4. 자가 취소 — DELETE /reservations/{reservationId}

### Request

```http
DELETE /reservations/42 HTTP/1.1
X-User-Id: user-123
```

### Response 204 No Content

### Errors

- 400 `not owner` — userId 불일치
- 400 `cannot cancel non-HELD reservation` — PAID/EXPIRED/CANCELLED 상태

## 5. PG Mock 환경변수

`application.yml`:

```yaml
ticketing:
  pgmock:
    success-rate: 1.0           # 0.0 ~ 1.0, 결제 성공률
    duplicate-callbacks: 0      # 같은 callback을 N번 추가 발사 (멱등성 테스트)
    callback-url: http://localhost:8080/payments/callback
```

테스트 시 환경변수로 override:

```bash
TICKETING_PGMOCK_DUPLICATE_CALLBACKS=10 ./gradlew :basic:bootRun
```

## 6. 인증 mock

`X-User-Id` 헤더로 사용자 식별. 실제 JWT 검증 없음.

```http
X-User-Id: any-string-up-to-64-chars
```

Stage 4 또는 별도 학습에서 진짜 IdP 통합.

## 7. curl 예시 (manual smoke test)

```bash
# 1. 구역 조회
curl http://localhost:8080/events/1/schedules/1/sections

# 2. VIP 구역 (id=1) 좌석 첫 100개
curl 'http://localhost:8080/sections/1/seats?status=AVAILABLE&page=0&size=100'

# 3. 좌석 1 예매
curl -X POST http://localhost:8080/seats/1/reservations \
  -H "X-User-Id: user-test"

# 4. 결제 요청 (idempotency key)
curl -X POST http://localhost:8080/payments \
  -H "X-User-Id: user-test" \
  -H "Idempotency-Key: idem-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d '{"reservationId": 1, "amount": 250000, "method": "CARD"}'

# 5. 1초 후 자동 callback (PG mock) → seat.status=SOLD

# 6. 예약 조회
curl http://localhost:8080/reservations/1
```
