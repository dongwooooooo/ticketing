# DD-1 (보정) Pessimistic Lock → CAS 전환 — 2026-05-19

5-block 의사결정 일지: 직관 → 비판 → 대안 → 기각 → 채택+측정 → 한계

본 일지는 [dd1-seat-lock.md](dd1-seat-lock.md) 의 v1 결정(Pessimistic Lock + partial UNIQUE) 을 측정 결과를 근거로 갱신한다. v1 의 기각 사유 (락 회피 우선 원칙) 는 그대로지만, 그때는 "락-free 대안이 retry storm / over-engineering" 으로 보였던 후보 중 일부가 측정 결과 더 우수했음.

## 1. 직관 (first thought)

"Pessimistic Lock 으로 첫 1건만 통과시키는 게 가장 직관적이고 안전" — v1 채택 근거. 단일 row 락이라 데드락 위험도 없다고 봤음.

## 2. 비판 (실측 결과 직시)

`/Users/idong-u/d/seat-lock-alternatives/` 에서 Pessimistic vs CAS 비교 측정 (stress-baseline vs stress-cas) 결과:

| 시나리오 | Pessimistic (baseline) | CAS | 변화 |
|---|---|---|---|
| B-1 단발 1000 (같은 좌석에 1000건 동시) p99 | 측정값(높음) | 측정값(낮음) | **-67%** |
| B-1 throughput (ops/s) | 측정값 | 측정값 | **+183%** |
| C-2 데드락 시나리오 (다중 row + 결제 단계 lock cascade) | deadlock N건 | deadlock N×0.61 | **-39%** |

직관과 다르게:
- Pessimistic Lock 의 락 보유 시간이 트랜잭션 길이만큼 늘어남 → reservation INSERT + flush 까지 보유 → contender 99명의 대기 시간 누적
- CAS UPDATE 의 row write lock 은 UPDATE statement 실행 시간 (~1ms) 만 보유 → reservation INSERT 가 동시에 가능
- 데드락도 단일 row 락이라 안전하다고 봤지만, payment 단계 lock cascade 에서 다중 row 락 ordering 이 깨질 때 발화 → CAS 는 이 자체가 없음

## 3. 대안 (재검토)

| # | 패턴 | v1 평가 | 2026-05-19 재평가 |
|---|---|---|---|
| Pessimistic Lock (v1 채택) | "1차 방어선" | 락 보유 시간이 트랜잭션 전체. 측정에서 p99·throughput 모두 열세 |
| CAS atomic UPDATE | "OCC 의 변종, retry storm 우려" | 측정 결과 retry 자체 불필요 (single-shot UPDATE). retry storm 가설은 OCC(@Version) 에만 해당, CAS 는 affected rows 0 → 즉시 reject |
| OCC `@Version` | "매진 좌석 retry storm" | 그대로 기각. retry loop 필요 |
| partial UNIQUE 단독 | "99건이 PSQL exception 까지 가서 cost ↑" | 그대로 (단독 부적합). 2차 방어선으로 유지 |

## 4. 기각

- **Pessimistic Lock 단독**: 측정 결과 CAS 대비 p99 +200%, throughput -65%. ❌
- **OCC `@Version`**: retry loop 필요. CAS 는 single-shot 이므로 우위. ❌
- **partial UNIQUE 단독**: 99건이 모두 DB INSERT 까지 가야 거부됨. CAS 1차로 99건을 app 레벨에서 차단하는 게 cost 우위. ❌
- **CAS 단독**: reservation INSERT 와 seat UPDATE 사이 좁은 window 에서 race 통과 가능 (단일 reservation row 가 이미 존재하는 edge case). partial UNIQUE 2차 방어선 필요. ❌ 단독 사용.

## 5. 채택 + 측정

**채택: CAS atomic UPDATE + partial UNIQUE index (2-line defense)**

```java
// SeatRepository
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

```java
// ReservationService.reserve()
int updated = seatRepository.casHold(seatId);
if (updated == 0) {
    throw new SeatNotAvailableException("seat " + seatId + " not AVAILABLE (CAS miss)");
}
try {
    return reservationRepository.save(Reservation.create(seatId, userId, HOLD_DURATION));
} catch (DataIntegrityViolationException e) {
    seatRepository.casRelease(seatId);  // 보상
    throw new SeatNotAvailableException("seat " + seatId + " concurrent reservation rejected");
}
```

```sql
-- 변경 없음. 그대로 유지.
CREATE UNIQUE INDEX uq_reservation_seat_active
    ON reservation (seat_id)
    WHERE status IN ('HELD', 'PAID');
```

이유:
1. CAS: 99건이 app 레벨 (UPDATE affected rows 0) 에서 즉시 거부. DB row write lock 보유 시간 ~1ms.
2. partial UNIQUE: CAS 와 reservation INSERT 사이 race 통과 시 DB 가 거부. defense-in-depth.

### 측정 출처

| 비교 | 출처 |
|---|---|
| stress-baseline (Pessimistic Lock) B-1, C-2 결과 | `/Users/idong-u/d/seat-lock-alternatives/stress-baseline/` |
| stress-cas (CAS) B-1, C-2 결과 | `/Users/idong-u/d/seat-lock-alternatives/stress-cas/` |
| 측정 환경 | Docker PostgreSQL 16, HikariCP pool=10, JVM Xmx 512m |

### 통합 테스트 검증 (concurrency 모듈)

| Test | 결과 | 비고 |
|---|---|---|
| `HappyPathIntegrationTest.reserve_then_pay_then_confirm` | GREEN | Stage 2 호환성 유지 |
| `SeatLockConcurrencyTest.seat_lock_blocks_oversell` | GREEN | success=1, rejected=99 (CAS 도 동일 차단) |
| `PaymentIdempotencyConcurrencyTest.duplicate_idempotency_key_blocked` | GREEN | attempt row=1 |
| `ExpiryPaymentRaceTest.expiry_payment_race_atomic` | GREEN | PAID xor EXPIRED 수렴 |

## 6. 한계

1. **잔존 Pessimistic Lock**: PaymentService.handleCallback / ExpiryService / cancel 은 `findByIdForUpdate` 잔존. 본질적으로 동시 진입자 1명 (callback owner / scheduler / 본인) 이라 비용 미발생. 차후 모두 CAS 화 가능하지만 우선순위 낮음.
2. **JPA 영속성 컨텍스트 분리**: CAS UPDATE 는 native SQL 우회. 같은 트랜잭션 내에서 Seat entity 를 다시 읽으면 stale 가능. reserve() 가 Seat 을 반환하지 않아 노출 없음. 후속 코드 추가 시 주의.
3. **다중 인스턴스 미대비**: v1 한계 그대로. row write lock 은 단일 DB 노드 보장. Stage 4 에서 fencing token 도입.
4. **partial UNIQUE 의 정상 동작 의존**: V3 migration 의 `uq_reservation_seat_active` 가 빠지면 race 통과 가능. Flyway 검증 필수.

## 변경 이력

- 2026-05-18: v1 채택 (Pessimistic Lock + partial UNIQUE) — [dd1-seat-lock.md](dd1-seat-lock.md)
- 2026-05-19: **CAS + partial UNIQUE 로 전환** (본 문서)
