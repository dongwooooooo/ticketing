# Stage 1 → Stage 2 변경 사항

본 문서는 `basic/` 대비 `concurrency/`에서 실제로 바뀐 코드 영역을 1:1 매핑한다.

## 1. DB 스키마 추가 (Flyway V3)

**파일**: `concurrency/src/main/resources/db/migration/V3__concurrency_constraints.sql`

```sql
-- 결제 멱등성: 같은 key로 두 번째 INSERT는 DB가 거부
ALTER TABLE payment_attempt
    ADD CONSTRAINT uq_payment_attempt_key UNIQUE (idempotency_key);

-- 좌석 점유 race의 2차 방어선: partial UNIQUE index
-- 같은 좌석에 HELD 또는 PAID row가 2건이면 DB가 거부
-- Pessimistic Lock이 실패해도(예: skip locked) DB constraint가 oversell 차단
CREATE UNIQUE INDEX uq_reservation_seat_active
    ON reservation (seat_id)
    WHERE status IN ('HELD', 'PAID');
```

partial unique index를 선택한 이유: `status IN ('HELD','PAID')` 조건이 무한히 누적되는 EXPIRED/CANCELLED row와 unique 경합하지 않도록.

## 2. SeatRepository — CAS atomic UPDATE 도입 (2026-05-19 갱신)

**파일**: `concurrency/src/main/java/com/dongwoo/ticketing/repository/SeatRepository.java`

```java
@Modifying
@Query(value = "UPDATE seat SET status='HELD', updated_at=now() " +
               "WHERE id=:id AND status='AVAILABLE'",
       nativeQuery = true)
int casHold(@Param("id") Long id);

@Modifying
@Query(value = "UPDATE seat SET status='AVAILABLE', updated_at=now() " +
               "WHERE id=:id AND status='HELD'",
       nativeQuery = true)
int casRelease(@Param("id") Long id);
```

- `basic/`은 `findById`만 호출 → memory check → save (race 발생)
- `concurrency/` v1 (~2026-05-18): Pessimistic Lock 도입. `@Lock(PESSIMISTIC_WRITE)` 로 SELECT FOR UPDATE
- `concurrency/` v2 (2026-05-19): **CAS atomic UPDATE 로 교체**. lock-free, row write lock 보유 시간 ~1ms

v1 → v2 전환 근거 (seat-lock-alternatives 비교 측정):
- B-1 (단발 1000) p99 -67%, throughput +183%
- C-2 (데드락 시나리오) deadlock 발생 -39%
- lock-free → deadlock·lock_timeout 발화 자체가 없음

`findByIdForUpdate` 는 PaymentService.handleCallback / ExpiryService / cancel 의 단일 owner 경로 전용으로 잔존. 본질적으로 동시 진입자 1명이라 잠금 비용 무의미하지만 defense-in-depth 유지.

Pessimistic → CAS 전환 5블록 의사결정: [decision-journal/dd1-seat-lock-cas-switch.md](decision-journal/dd1-seat-lock-cas-switch.md)
v1 (Pessimistic 채택) 의사결정 원본: [decision-journal/dd1-seat-lock.md](decision-journal/dd1-seat-lock.md)

## 3. ReservationRepository — atomic UPDATE 2종 추가

**파일**: `concurrency/src/main/java/com/dongwoo/ticketing/repository/ReservationRepository.java`

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE Reservation r SET r.status = :nextStatus " +
       "WHERE r.id = :id AND r.status = :expectedStatus")
int updateStatusIfCurrent(Long id,
                          ReservationStatus expectedStatus,
                          ReservationStatus nextStatus);

@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query(value = "UPDATE reservation SET status='EXPIRED', updated_at=now() " +
               "WHERE status='HELD' AND expires_at < now()",
       nativeQuery = true)
int expireOverdue();
```

- `basic/`은 `setStatus`로 entity 변경 → `save` (READ → MODIFY → WRITE, lost update 가능)
- `concurrency/`는 단일 SQL UPDATE → affected rows 0 또는 1로 race 차단

`updateStatusIfCurrent`의 affected rows가 0이면 "이미 누군가 상태를 바꿈" → 본 트랜잭션은 no-op.

## 4. PaymentService — INSERT 실패 catch 패턴

**파일**: `concurrency/src/main/java/com/dongwoo/ticketing/service/PaymentService.java`

```java
PaymentAttempt attempt;
try {
    attempt = paymentAttemptRepository.saveAndFlush(
            PaymentAttempt.requesting(idempotencyKey));
} catch (DataIntegrityViolationException e) {
    var existing = paymentAttemptRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(...);
    return paymentRepository.findById(existing.getPaymentId()).orElseThrow(...);
}
Payment payment = paymentRepository.save(Payment.request(reservationId, amount));
attempt.linkPayment(payment.getId());
```

- `basic/`은 같은 key로 N번 INSERT 가능 (멱등성 없음)
- `concurrency/`는 DB UNIQUE가 race를 거부 → 1건만 통과, 나머지는 catch → 기존 결과 replay
- attempt INSERT를 payment INSERT보다 앞에 둬서 race-loser 99건이 orphan payment row를 남기지 않도록 함

락-free 패턴 채택 이유: [decision-journal/dd2-idempotency.md](decision-journal/dd2-idempotency.md)

## 5. PaymentService.handleCallback — atomic UPDATE로 lost update 차단

```java
int affected = reservationRepository.updateStatusIfCurrent(
        payment.getReservationId(), ReservationStatus.HELD, ReservationStatus.PAID);

if (affected == 0) {
    // 이미 만료/취소된 reservation — 환불 큐 (본 Lab은 로그만)
    log.warn("Payment success but reservation no longer HELD — refund needed.");
    payment.confirm();  // PG는 이미 차감, 별도 환불 처리
    return;
}
```

- `basic/`은 reservation.confirm() 호출 후 save → 만료 처리와 lost update 발생
- `concurrency/`는 atomic UPDATE로 두 트랜잭션 중 1건만 affected=1, 나머지는 affected=0

상태 전이 트리: [decision-journal/dd3-expiry-race.md](decision-journal/dd3-expiry-race.md)

## 6. ExpiryService — 단일 atomic UPDATE + 좌석 release 분리

**파일**: `concurrency/src/main/java/com/dongwoo/ticketing/service/ExpiryService.java`

```java
@Scheduled(fixedDelay = 5000)
@Transactional
public void expireOverdueReservations() {
    int affected = reservationRepository.expireOverdue();
    if (affected == 0) return;

    // EXPIRED로 막 바뀐 row의 seat_id 조회 → 좌석 복귀 (별도 lock)
    List<Long> seatIds = reservationRepository.findSeatIdsRecentlyExpired(since);
    for (Long seatId : seatIds) {
        seatRepository.findByIdForUpdate(seatId).ifPresent(seat -> {
            seat.release();
            seatRepository.save(seat);
        });
    }
}
```

- `basic/`은 `findAll().filter().forEach(setStatus)` 패턴 (트랜잭션 안에서 lost update)
- `concurrency/`는 atomic UPDATE 1발 + 좌석 release loop (좌석은 row lock으로 보호)

다중 인스턴스 중복 실행은 Stage 4 (ShedLock)에서.

## 7. ReservationService.reserve() — CAS + UNIQUE 2-line defense

```java
int updated = seatRepository.casHold(seatId);
if (updated == 0) {
    soldOutCache.markSoldOut(seatId);
    throw new SeatNotAvailableException("seat " + seatId + " not AVAILABLE (CAS miss)");
}
soldOutCache.markSoldOut(seatId);

try {
    Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
    return reservationRepository.save(reservation);
} catch (DataIntegrityViolationException e) {
    seatRepository.casRelease(seatId);
    throw new SeatNotAvailableException("seat " + seatId + " concurrent reservation rejected");
}
```

- 1차 (CAS): atomic UPDATE 의 affected rows 로 winner/loser 판별. lock-free.
- 2차 (partial UNIQUE): reservation INSERT 시 race 가 CAS 와 UNIQUE 사이 좁은 window 통과해도 DB 가 거부.
- UNIQUE 위반 catch 시 `casRelease` 로 seat status 보상 (HELD → AVAILABLE).

## 환경 차이

| 항목 | basic | concurrency |
|---|---|---|
| Application class | `BasicApplication` | `TicketingConcurrencyApplication` |
| Server port | 8080 | 8081 |
| PostgreSQL port | 5432 | 5433 |
| Docker container | `ticketing_basic_db` | `ticketing_concurrency_db` |
| Flyway 마이그레이션 | V1, V2 | V1, V2, **V3** |

## 다음 Stage로 넘기는 것

| 이슈 | Stage |
|---|---|
| 트래픽 폭주 시 backend 직격 | Stage 3 (queue) |
| 만료 스케줄러 다중 인스턴스 중복 실행 | Stage 4 (distributed, ShedLock) |
| 분산 환경 좌석 락 (Redis + fencing) | Stage 4 |
