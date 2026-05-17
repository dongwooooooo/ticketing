# Deep Dives — 측정 + 검증 계획

Stage 2의 3가지 핵심 race를 각각 패턴, 측정 시나리오, 가설, 합격선으로 분해.

## DD-1 좌석 동시 선점 (SCN-M-02)

### 문제

같은 좌석 1번에 동시 100개의 예매 요청이 들어왔을 때 정확히 1건만 HELD가 되어야 한다.

### 채택 패턴

**2-line defense**:
1. `@Lock(PESSIMISTIC_WRITE)`로 seat row를 트랜잭션 진입 시 lock
2. `reservation` partial UNIQUE index (`status IN ('HELD','PAID')`)가 DB 레벨 2차 방어

락만으로 충분하지 않은가? → DB constraint를 둘 다 두는 이유는 [decision-journal/dd1-seat-lock.md](decision-journal/dd1-seat-lock.md) §3 참조 (앱 버그/스킵, 다중 인스턴스 미래 대비).

### 측정 시나리오

**Test**: `SeatLockConcurrencyTest.seat_lock_blocks_oversell`

| Variable | Value |
|---|---|
| Seat ID | 100 |
| Concurrent threads | 100 |
| Latch | `start.await()`로 동시 발사 |

### 가설 & 합격선

| 항목 | 기대값 |
|---|---|
| `success.get()` | 1 |
| `rejected.get()` | 99 |
| DB의 `HELD` count for seat_id=100 | 1 |
| p99 응답 시간 (낙첨) | < 500ms |
| HikariCP active connection peak | < pool size × 0.8 |

### 추가 검증 (선택)

- Pessimistic Lock 제거 후 partial UNIQUE만 → 여전히 1건만 통과하는가? (defense-in-depth 검증)
- Pessimistic Lock만 + UNIQUE 제거 → 1건만 통과하는가?
- 둘 다 제거 (basic 동작) → oversell 재현되는가? (회귀 baseline)

## DD-2 결제 idempotency (SCN-U-05)

### 문제

같은 `Idempotency-Key`로 동시 100건의 결제 요청이 들어와도 `PaymentAttempt` row가 정확히 1건이어야 한다 (PG에 N번 차감 금지).

### 채택 패턴

- `payment_attempt.idempotency_key` UNIQUE constraint
- `INSERT` 시도 → `DataIntegrityViolationException` catch → 기존 응답 replay

**락이 아닌 이유**: 결제 API는 외부 의존(PG)이 있을 수 있고 트랜잭션 길이가 길다. Pessimistic Lock으로 묶으면 같은 reservation 노리는 다른 요청들도 대기 → pool 고갈로 cascade. UNIQUE constraint는 lock-free.

상세: [decision-journal/dd2-idempotency.md](decision-journal/dd2-idempotency.md)

### 측정 시나리오

**Test**: `PaymentIdempotencyConcurrencyTest.duplicate_idempotency_key_blocked`

| Variable | Value |
|---|---|
| Reservation | 신규 (HELD 상태) |
| Idempotency-Key | `idem-test-{nanoTime}` (단일 key) |
| Concurrent threads | 100 |

### 가설 & 합격선

| 항목 | 기대값 |
|---|---|
| `paymentAttempt` row count (key 기준) | 1 |
| `Payment` row count (reservation 기준) | 1 |
| `success.get() + errors.get()` (그러나 `request()`는 멱등 hit 시 성공으로 처리됨) | 100 |
| 99건의 처리는 동일한 `paymentId` 반환 | true |

### 추가 검증 (선택)

- 다른 key로 100건 → attempt 100건 (멱등성 false positive 검증)
- 같은 key + 다른 amount → 어떻게 처리할 것인가? (현재는 amount 무시하고 기존 응답 반환 — 운영상 risky)

## DD-3 만료-callback race (SCN-S-02)

### 문제

`ExpiryService`가 만료 처리하는 동시에 PG callback이 도착하면 reservation의 status가 비결정적이 될 수 있다.

- Lost update: callback이 PAID로 쓴 후 만료 스케줄러가 EXPIRED로 덮음 → 환불 누락
- 반대: 만료가 EXPIRED로 쓴 후 callback이 PAID로 덮음 → ghost 좌석 (이미 환불해야 할 자리가 결제 확정)

### 채택 패턴

**Atomic UPDATE WHERE status = expected**:

```sql
UPDATE reservation SET status='PAID' WHERE id=? AND status='HELD'
UPDATE reservation SET status='EXPIRED' WHERE status='HELD' AND expires_at < now()
```

두 트랜잭션이 같은 row를 노려도 row-level lock + WHERE 조건 평가로 단 1건만 `affected rows == 1`, 나머지는 0.

callback의 affected가 0이면 → 이미 만료/취소 → 환불 큐 enqueue (본 Lab은 로그).

상세 상태 전이도: [decision-journal/dd3-expiry-race.md](decision-journal/dd3-expiry-race.md)

### 측정 시나리오

**Test**: `ExpiryPaymentRaceTest.expiry_payment_race_atomic`

| Variable | Value |
|---|---|
| Reservation | HELD + `expires_at` 과거로 강제 |
| Concurrent threads | 2 (만료 스레드 + callback 스레드) |
| Latch | `start.await()`로 동시 발사 |

### 가설 & 합격선

| 항목 | 기대값 |
|---|---|
| 최종 `reservation.status` | `PAID` 또는 `EXPIRED` (정확히 둘 중 하나) |
| 동일 row에 affected=1인 트랜잭션 수 | 정확히 1 |
| 다른 트랜잭션의 affected | 0 |
| seat 좌석 정합성 (PAID면 seat=BOOKED, EXPIRED면 seat=AVAILABLE) | 보장 |

### 추가 검증 (선택)

- callback이 affected=0 받으면 환불 로그가 찍히는가?
- 만료 처리 중 callback이 도착해도 seat release loop가 race 없이 동작하는가? (seat은 `findByIdForUpdate`로 보호)
- 같은 시나리오 100회 반복 (flaky 검증)

## 측정 환경 (실측 시 기록)

| 항목 | 값 |
|---|---|
| OS | macOS 25.2 (darwin) |
| JVM | Java 25 (Corretto) |
| PostgreSQL | 16 (docker, port 5433) |
| HikariCP | default (max=10) |
| 측정 방식 | JUnit + Testcontainers, `@ServiceConnection` |

부하 (k6) 측정은 Stage 3에서. 본 Stage는 race 발생/차단 binary 검증이 우선.
