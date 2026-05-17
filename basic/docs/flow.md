# 전체 프로세스 플로우

Stage 1 (basic) 단일 서버에서 일어나는 모든 시나리오를 시퀀스 + 상태 전이로 정리.

각 시나리오마다:
- 누가 어떤 순서로 호출하는가
- DB가 어떤 변화를 겪는가
- 어떤 race condition이 발생할 수 있는가 (Stage 2~4에서 해결)

## 0. 도메인 상태 전이 종합

```
SEAT:          AVAILABLE ─hold──> HELD ─confirm──> SOLD
                            │             │
                            ├─release─────┘
                            └────────────────── (release on cancel/expire/fail)

RESERVATION:   HELD ─pay confirmed──> PAID
                  ├─timeout──────> EXPIRED
                  └─user cancel──> CANCELLED

PAYMENT:       REQUESTED ─callback SUCCESS──> CONFIRMED
                       └─callback FAIL─────> FAILED
```

상태 전이의 모든 가능한 조합을 single-thread 가정으로 작성. 멀티스레드 race 발생 지점은 §6에 별도 표시.

---

## 1. Happy Path — 예매 → 결제 → 확정

가장 자주 일어나는 정상 시나리오.

### 1.1 좌석 조회

```
Client ──GET /events/{eventId}/schedules/{scheduleId}/sections──> EventController
                                                                       │
                                                                       └─ sectionRepository.findByScheduleId()
                                                                       │
Client <───── [Section 목록 (VIP/R/S/A/스탠딩)] ────────────────────────┘

Client ──GET /sections/{sectionId}/seats?status=AVAILABLE&page=0&size=100──> SeatController
                                                                                   │
                                                                                   └─ seatQueryService.findSeats()
                                                                                   │
Client <───── [Page<SeatResponse> AVAILABLE 좌석 100개] ───────────────────────────┘
```

DB read-only. 트랜잭션 격리수준 영향 없음.

### 1.2 좌석 예매 (HOLD)

```
Client ──POST /seats/{seatId}/reservations  Header: X-User-Id: user-123──> ReservationController
                                                                                  │
                                                                                  └─ reservationService.reserve(seatId, "user-123")
                                                                                       │
                                                                                       ├─ [@Transactional 시작]
                                                                                       │
                                                                                       ├─ seatRepository.findById(seatId)
                                                                                       │     ── SELECT * FROM seat WHERE id=? ── (락 없음)
                                                                                       │
                                                                                       ├─ if (seat.status != AVAILABLE) throw
                                                                                       │
                                                                                       ├─ seat.hold()  // status=HELD (메모리 상)
                                                                                       │
                                                                                       ├─ seatRepository.save(seat)
                                                                                       │     ── UPDATE seat SET status='HELD' WHERE id=?
                                                                                       │
                                                                                       ├─ reservationRepository.save(Reservation.create(...))
                                                                                       │     ── INSERT INTO reservation (..., status='HELD', expires_at=now+5m)
                                                                                       │
                                                                                       └─ [@Transactional commit]
                                                                                       │
Client <───── 201 { reservationId, seatId, expiresAt } ───────────────────────────────┘
```

**Race 발생 지점 (§6.1)**: `findById` 후 `seat.status` 검사와 `save` 사이.

### 1.3 결제 요청

```
Client ──POST /payments──> PaymentController
  Header: Idempotency-Key: idem-abc-123
  Body: { reservationId, amount: 250000, method: "CARD" }
                          │
                          └─ paymentService.request(reservationId, 250000, "idem-abc-123", null)
                               │
                               ├─ [@Transactional 시작]
                               │
                               ├─ paymentAttemptRepository.findFirstByIdempotencyKey("idem-abc-123")
                               │     ── SELECT * FROM payment_attempt WHERE idempotency_key=? LIMIT 1
                               │
                               ├─ 없음 → 새 Payment + PaymentAttempt 생성
                               │     ├─ paymentRepository.save(Payment.request(reservationId, 250000))
                               │     │     ── INSERT INTO payment (..., status='REQUESTED')
                               │     ├─ paymentAttemptRepository.save(PaymentAttempt.of(paymentId, "idem-abc-123", null))
                               │     │     ── INSERT INTO payment_attempt (..., status='REQUESTED')
                               │
                               ├─ gateway.firePaymentCallback(paymentId)   [@Async]
                               │     └─ [별도 스레드] Thread.sleep(1000) → POST /payments/callback { paymentId, result: "SUCCESS" }
                               │
                               └─ [@Transactional commit]
                          │
Client <── 202 Accepted { paymentId, status: 'REQUESTED' } ──┘
```

**Race 발생 지점 (§6.2)**: `findFirstByIdempotencyKey` 후 `INSERT` 사이.

### 1.4 결제 Callback (PG → 우리 서버)

```
[PG mock (1초 후)] ──POST /payments/callback { paymentId: 99, result: "SUCCESS" }──> PaymentController
                                                                                            │
                                                                                            └─ paymentService.handleCallback(callback)
                                                                                                 │
                                                                                                 ├─ [@Transactional 시작]
                                                                                                 │
                                                                                                 ├─ paymentRepository.findById(99)
                                                                                                 │     ── SELECT * FROM payment WHERE id=99
                                                                                                 │
                                                                                                 ├─ payment.confirm()
                                                                                                 │     ── UPDATE payment SET status='CONFIRMED', approved_at=now()
                                                                                                 │
                                                                                                 ├─ reservationRepository.findById(reservationId)
                                                                                                 │     ── SELECT
                                                                                                 │
                                                                                                 ├─ reservation.markPaid()
                                                                                                 │     ── UPDATE reservation SET status='PAID'
                                                                                                 │
                                                                                                 ├─ seatRepository.findById(seatId)
                                                                                                 ├─ seat.confirm()
                                                                                                 │     ── UPDATE seat SET status='SOLD'
                                                                                                 │
                                                                                                 └─ [@Transactional commit]
```

**Race 발생 지점 (§6.3, §6.4)**: callback 중복 + 만료 처리와 동시 진입.

---

## 2. 분기 — 사용자 자가 취소 (HELD 상태)

```
Client ──DELETE /reservations/{reservationId}  Header: X-User-Id──> ReservationController
                                                                          │
                                                                          └─ reservationService.cancel(reservationId, userId)
                                                                               │
                                                                               ├─ [@Transactional]
                                                                               │
                                                                               ├─ reservation = repo.findById()
                                                                               ├─ if (!owner || !HELD) throw
                                                                               │
                                                                               ├─ reservation.markCancelled()
                                                                               │     ── UPDATE reservation SET status='CANCELLED'
                                                                               │
                                                                               ├─ seat.release()
                                                                               │     ── UPDATE seat SET status='AVAILABLE'
                                                                               │
                                                                               └─ commit
                                                                          │
Client <── 204 No Content ──┘
```

PAID 후 취소는 본 레포 범위 밖 (환불 = Stage 3+).

---

## 3. 분기 — 결제 실패 (PG callback FAIL)

```
[PG mock] ──POST /payments/callback { paymentId, result: "FAIL" }──> PaymentService.handleCallback()
                                                                              │
                                                                              ├─ payment.fail()       UPDATE payment SET status='FAILED'
                                                                              ├─ reservation.markCancelled()   UPDATE reservation SET status='CANCELLED'
                                                                              └─ seat.release()       UPDATE seat SET status='AVAILABLE'
```

---

## 4. 분기 — 예약 만료 (5분 후)

```
[ExpiryService @Scheduled(fixedDelay=5000)]   매 5초 호출
  │
  └─ [@Transactional]
       │
       ├─ reservationRepository.findByStatusAndExpiresAtBefore(HELD, now())
       │     ── SELECT * FROM reservation WHERE status='HELD' AND expires_at < now()
       │
       ├─ for each overdue:
       │     ├─ r.markExpired()     UPDATE reservation SET status='EXPIRED'
       │     └─ seat.release()      UPDATE seat SET status='AVAILABLE'
       │
       └─ commit
```

**Race 발생 지점 (§6.4)**: 만료 처리와 결제 callback이 동시 진입 시 lost update.

---

## 5. PG Mock 콜백 시뮬레이션

`MockPaymentGateway` 동작:

```
PaymentService.request() 호출
  │
  └─ gateway.firePaymentCallback(paymentId)   [@Async, 별도 스레드]
       │
       ├─ Thread.sleep(1000)                  // PG 처리 시간 시뮬레이션
       │
       ├─ result = (랜덤 < success-rate) ? "SUCCESS" : "FAIL"
       │
       ├─ POST http://localhost:8080/payments/callback { paymentId, result }   ── 1차 발사
       │
       └─ for (i=0; i<duplicate-callbacks; i++) {
             Thread.sleep(50)
             POST .../callback (동일 paymentId, 동일 result)                     ── N차 재발사
          }
```

환경변수 제어:
- `ticketing.pgmock.success-rate=1.0` — 100% 성공 (기본값)
- `ticketing.pgmock.success-rate=0.5` — 절반 실패 시뮬레이션
- `ticketing.pgmock.duplicate-callbacks=10` — 같은 callback을 10회 추가 발사 (멱등성 테스트용)

---

## 6. 의도적 race condition 발생 지점

본 레포는 아래 race를 해결하지 않는다. Stage 2에서 각각 해결.

### 6.1 좌석 선점 race (`I-001`)

위치: `ReservationService.reserve()` line 38~50

```
Thread A: findById(1)  → seat.status == AVAILABLE
Thread B: findById(1)  → seat.status == AVAILABLE   ← Thread A의 save 전
Thread A: save(seat)   → UPDATE seat SET status='HELD'
Thread B: save(seat)   → UPDATE seat SET status='HELD'   ← 덮어쓰기
Thread A: INSERT reservation (seatId=1, userId='A', status='HELD')
Thread B: INSERT reservation (seatId=1, userId='B', status='HELD')   ← oversell!
```

결과: 좌석 1에 사용자 2명 예매 성공. DB 정합성 위배.

Stage 2 해결: `@Lock(PESSIMISTIC_WRITE)` 또는 `UNIQUE (seat_id) WHERE status='HELD'` partial index + INSERT ON CONFLICT.

### 6.2 결제 멱등성 race (`I-002`)

위치: `PaymentService.request()` line 32~40

```
Thread A: findFirstByIdempotencyKey("key-X")  → empty
Thread B: findFirstByIdempotencyKey("key-X")  → empty   ← A의 INSERT 전
Thread A: INSERT payment + INSERT payment_attempt(key='key-X')
Thread B: INSERT payment + INSERT payment_attempt(key='key-X')   ← 중복 결제!
```

결과: 같은 idempotency-key로 결제 2건 생성. PG 호출 2번, 사용자 2배 차감.

Stage 2 해결: `payment_attempt.idempotency_key UNIQUE` + INSERT ON CONFLICT.

### 6.3 결제 callback 중복

위치: `PaymentService.handleCallback()` line 47~70

```
Callback 1: payment.confirm()      → UPDATE payment SET status='CONFIRMED'
Callback 2: payment.confirm()      → UPDATE payment SET status='CONFIRMED' (idempotent operation, but...)
            reservation.markPaid() → UPDATE reservation SET status='PAID'
            seat.confirm()         → UPDATE seat SET status='SOLD'
```

callback 2회면 같은 UPDATE 2회 실행. 상태 변경 자체는 같지만 audit log/이벤트 발행 시 중복 발생.

Stage 2 해결: 상태 전이를 `UPDATE WHERE status='REQUESTED'` 조건부로 + affected rows 판별.

### 6.4 만료-결제 lost update (`I-003`)

위치: `ExpiryService.expireOverdueReservations()` + `PaymentService.handleCallback()`

```
시점 T+0:   ExpiryService scheduler 발사
            ├─ SELECT * FROM reservation WHERE status='HELD' AND expires_at < now()
            ├─ for each: r.markExpired() (메모리 상)
시점 T+10ms: PG callback 도착 → PaymentService.handleCallback()
            ├─ SELECT * FROM payment ← 메모리 상 reservation.status는 'HELD'
            ├─ reservation.markPaid()
시점 T+20ms: ExpiryService commit → UPDATE reservation SET status='EXPIRED'
시점 T+30ms: PaymentService commit → UPDATE reservation SET status='PAID'  ← 이게 이김
```

JPA가 dirty checking + flush 순서에 따라 결과가 비결정적. 최악의 경우 PAID가 EXPIRED로 덮임.

Stage 2 해결: `UPDATE reservation SET status='X' WHERE id=? AND status='HELD' RETURNING ...` atomic UPDATE + affected rows == 0 판별.

---

## 7. 다음 스테이지로의 이동

본 레포에서 §6.1~§6.4 race를 재현 측정 (성공률, 실패율, oversell 건수)한 뒤:

1. 측정 결과를 `docs/measurements/stage1-race-*.txt`에 저장
2. 같은 도메인 모델을 `concurrency/` 모듈로 복사
3. Stage 2에서 락/UNIQUE/atomic UPDATE 도입
4. 같은 race repro 테스트로 측정 → Stage 1과 비교
