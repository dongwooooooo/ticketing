# DD-1 좌석 동시 선점 — Pessimistic Lock + partial UNIQUE index (v1, 2026-05-18)

> **상태**: SUPERSEDED — 2026-05-19 CAS atomic UPDATE 로 전환. 본 문서는 v1 결정의 기록으로 보존.
> 후속 결정: [dd1-seat-lock-cas-switch.md](dd1-seat-lock-cas-switch.md)

5-block 의사결정 일지: 직관 → 비판 → 대안 → 기각 → 채택+측정 → 한계

## 1. 직관 (first thought)

"같은 좌석을 두 명이 동시에 잡는 race니까 락이 필요하다 → Pessimistic Lock."

## 2. 비판 (락 회피 우선 원칙)

본 프로젝트 대전제: **현업은 락을 가능한 한 피한다**. 이유:

- Lock holding 시간이 트랜잭션 길이만큼 늘어남
- 외부 호출이 트랜잭션 안에 들어가면 cascade pool exhaustion (anti-pattern §1.5)
- 락 충돌 시 대기 → p99 폭증
- 다중 인스턴스로 가면 row lock은 무력 (분산 락 Kleppmann fencing 필요)

"좌석 lock 없이 풀 수 없는가?" — 이게 직관에 대한 1차 비판.

## 3. 대안 (락-free 후보)

| # | 패턴 | 평가 |
|---|---|---|
| A | partial UNIQUE index 단독 | DB가 거부 → race 차단. 단점: 99건이 PSQL exception까지 가서 cost ↑ |
| B | OCC (`@Version`) | retry loop 필요. 비매진 좌석은 충돌 0, 매진 좌석은 retry storm |
| C | `INSERT ... ON CONFLICT DO NOTHING` | partial UNIQUE 활용 가능. PostgreSQL native, JPA 우회 필요 |
| D | Redis SETNX | 단일 노드에서 over-engineering. Stage 4 분산락 주제 |
| E | SKIP LOCKED | 동일 좌석 1건만 lock, 나머지는 즉시 next available 받음. 본 시나리오는 "특정 좌석"이라 적합 X |
| F | 단일-writer 큐 | Stage 3 주제 (대기열) |

## 4. 기각 (대안별 기각 이유)

- **A 단독**: 99건이 모두 DB까지 가서 PSQL constraint violation 받음 → log 폭주, p99 악화. App-level 거부가 더 깔끔. ❌ 단독 사용
- **B OCC**: 매진 좌석 100명 동시 시 99회 retry. 평균 retry 2~3회로 수렴해도 추가 RTT 발생. 100% 매진 가정에선 retry storm. ❌
- **C INSERT ... ON CONFLICT**: 매우 좋지만 JPA에서 native query로 빠져나가야 함. 향후 도메인 로직 추가 시 응집도 ↓. ❌ (정확히는 deferred)
- **D Redis**: 단일 노드 가정 위반. Stage 4로 미룸. ❌
- **E SKIP LOCKED**: API 시나리오 mismatch. ❌
- **F 단일-writer**: Stage 3 주제. 본 stage 범위 초과. ❌

## 5. 채택 + 측정

**채택: Pessimistic Lock + partial UNIQUE index (2-line defense)**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Seat s WHERE s.id = :id")
Optional<Seat> findByIdForUpdate(@Param("id") Long id);
```

```sql
CREATE UNIQUE INDEX uq_reservation_seat_active
    ON reservation (seat_id)
    WHERE status IN ('HELD', 'PAID');
```

이유:
1. Pessimistic Lock: 99건이 DB까지 가지 않고 app에서 대기 → 1건만 통과 → 나머지는 빠른 거부. 트랜잭션 길이가 짧다(외부 호출 없음).
2. partial UNIQUE: app 레벨에서 lock이 새거나 SKIP될 경우(예: timeout, race in scheduler)에도 DB가 oversell을 거부. defense-in-depth.

측정 시나리오 + 합격선: [../deep-dives.md](../deep-dives.md) §DD-1.

### 측정 결과 (실측 후 기록)

| 시도 | success | rejected | DB HELD count | p99 (낙첨) | HikariCP peak |
|---|---|---|---|---|---|
| Pessimistic only | _측정 후_ | _측정 후_ | _측정 후_ | _측정 후_ | _측정 후_ |
| Partial UNIQUE only | _측정 후_ | _측정 후_ | _측정 후_ | _측정 후_ | _측정 후_ |
| 둘 다 (현재) | _측정 후_ | _측정 후_ | _측정 후_ | _측정 후_ | _측정 후_ |
| basic (둘 다 없음) | _측정 후_ | _측정 후_ | _측정 후_ | _측정 후_ | _측정 후_ |

## 6. 한계 (남은 리스크)

1. **다중 인스턴스 미대비**: 본 stage는 단일 인스턴스 가정. 인스턴스 N개에선 row lock도 분산 환경에서 보장되지만, 인스턴스 간 ack 지연 등으로 GC pause + 클럭 분리 시나리오는 partial UNIQUE만이 안전. Stage 4에서 fencing token 도입.
2. **Pessimistic Lock의 cascade 위험**: 락 보유 중 추가 작업이 늘어나면 같은 좌석 노리는 99건이 대기 → p99 폭증. 본 stage는 트랜잭션 안에 외부 호출 없도록 분리. 위반 시 anti-pattern §1.5.
3. **partial UNIQUE의 분리 갱신 어려움**: status 변경 시 unique 경합. EXPIRED/CANCELLED는 status에서 빠져있어 동일 좌석 재예약 가능 (정상 동작이지만 추후 audit 시 혼동 가능).
4. **데드락 위험**: 본 모듈은 단일 row lock이라 데드락 없음. 다중 row lock(예: 좌석+예매 동시) 도입 시 ordering 필요.
