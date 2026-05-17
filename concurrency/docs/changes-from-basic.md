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

## 2. SeatRepository — Pessimistic Lock 도입

**파일**: `concurrency/src/main/java/com/dongwoo/ticketing/repository/SeatRepository.java`

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Seat s WHERE s.id = :id")
Optional<Seat> findByIdForUpdate(@Param("id") Long id);
```

- `basic/`은 `findById`만 호출 → memory check → save (race 발생)
- `concurrency/`는 트랜잭션 시작 시 row lock 획득 → 같은 좌석 노리는 다른 트랜잭션은 대기

Pessimistic을 선택한 이유 + 락 회피 시도 기록: [decision-journal/dd1-seat-lock.md](decision-journal/dd1-seat-lock.md)

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

## 7. ReservationService — DataIntegrityViolation fallback

```java
try {
    seatRepository.save(seat);
    return reservationRepository.save(Reservation.held(seatId, userId, expiresAt));
} catch (DataIntegrityViolationException e) {
    throw new IllegalStateException("seat already reserved", e);
}
```

partial UNIQUE index가 race를 거부했을 때 (Pessimistic Lock이 빠진 케이스에 대비) — defense in depth.

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
