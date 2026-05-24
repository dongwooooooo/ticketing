# Stage 4 — Distributed

Stage 3 (queue 모듈) 베이스 위에 분산 컴포넌트 4종을 더한 모듈.

## 4단계 진화에서 위치

| 단계 | 모듈 | 핵심 보강 |
|---|---|---|
| 1. basic | basic | 도메인 + happy path |
| 2. concurrency | concurrency | 동시성 정합성 (CAS, partial UNIQUE, idempotency) |
| 3. queue | queue | 대기열 + admit gate (단일 인스턴스) |
| 4. **distributed** | **distributed** | **수평 확장 + 분산 정합성** |

## Stage 4 신규 구성

### A. Redis 분산 큐

- `RedisWaitingQueue` — Redis ZSET 으로 대기열 외부화
- ZADD score=epoch ms / ZRANGE → ZREM + SADD admitted 가 Lua atomic
- 모든 backend 인스턴스가 같은 Redis 를 봄 → state 분산 일관성 자연 확보
- 출처: `queue-alternatives/queue-a-redis-sorted-set` 코드 차용

### B. Redis 분산 락 + fencing token

- `DistributedSeatLock`
  - `SET seat:lock:{id} {holder} NX EX 5` — best-effort 분산 락
  - `INCR seat:fence:{id}` — 단조 증가 fencing token (Redis 단일 shard atomic)
- DB 측 검증: `casHold(seatId, fence)` 가 `WHERE id=? AND status='AVAILABLE' AND :fence > lock_token`
- GC pause 시나리오:
  1. A 가 락 + fence=5 받음
  2. A 가 stop-the-world GC (>5초) → Redis 락 TTL 만료
  3. B 가 같은 좌석 락 + fence=6 받음. casHold(6) 성공 → lock_token=6
  4. A 깨어나서 casHold(5) 시도 → `5 > 6 == false` → affected=0 ⇒ 안전

### C. ShedLock leader election

- `@SchedulerLock(name="seat-expiry")` — ExpiryService 의 @Scheduled 가 인스턴스 N대 중 1대만 실행
- `@SchedulerLock(name="queue-dispatcher")` — admit dispatcher 도 동일
- 저장소: PostgreSQL `shedlock` 테이블 (V4 마이그레이션에서 생성)
- 인스턴스 다운 시 `lock_until` 지나면 다른 인스턴스가 인계

### D. Outbox 패턴

- 결제 callback handler 는 짧은 tx 안에 `outbox INSERT` 만 하고 200 OK
- `OutboxWorker` 가 `SELECT ... FOR UPDATE SKIP LOCKED` 폴링 → 실제 처리 (별도 tx)
- 멀티 인스턴스 worker 가 같은 row 잡지 않음 — throughput 자연 확장
- 처리 실패 시 attempts++ 후 PENDING 으로 복귀. 10회 초과 시 DEAD 마킹

## 모듈 구조

```
distributed/
├── build.gradle  (Redis + ShedLock 의존성 추가)
├── src/main/java/com/dongwoo/ticketing/
│   ├── DistributedApplication.java
│   ├── lock/
│   │   ├── DistributedSeatLock.java   (SETNX + INCR fence)
│   │   └── ShedLockConfig.java        (JDBC lock provider)
│   ├── queue/
│   │   ├── WaitingQueue.java
│   │   ├── RedisWaitingQueue.java     (Lua atomic ZSET 큐)
│   │   ├── InProcessWaitingQueue.java (호환용, 비활성 기본)
│   │   ├── WaitingQueueDispatcher.java (@SchedulerLock)
│   │   └── WaitingQueueMetrics.java
│   ├── outbox/
│   │   ├── OutboxEvent.java
│   │   └── OutboxRepository.java      (FOR UPDATE SKIP LOCKED)
│   ├── worker/
│   │   └── OutboxWorker.java          (@Scheduled 폴링)
│   ├── service/
│   │   ├── ReservationService.java    (분산 락 + fencing)
│   │   ├── PaymentService.java        (outbox INSERT 만)
│   │   └── ExpiryService.java         (@SchedulerLock)
│   └── repository/
│       └── SeatRepository.java        (casHold / casRelease / casConfirm)
└── src/main/resources/db/migration/
    ├── V1..V3                          (queue 모듈에서 복사)
    └── V4__distributed.sql             (seat.lock_token, outbox, shedlock)
```

## 테스트

| 테스트 | 검증 대상 | 결과 |
|---|---|---|
| DistributedSeatLockTest | 동시 100건 진입 → 1명만 락 / fence 단조 증가 | PASS |
| FencingTokenTest | stale holder 의 UPDATE 가 fence 검증으로 affected=0 | PASS |
| DistributedQueueTest | Redis ZSET 큐 정합성, 인스턴스 간 state 공유 | PASS (5개 케이스) |
| OutboxReconciliationTest | 폴링 + 실패 재시도 + DEAD 마킹 | PASS |

총 11 tests, 0 failures. testcontainers 기반 (postgres:16 + redis:7-alpine).

```bash
./gradlew :distributed:test
```

## 측정 인프라

`/Users/idong-u/d/ticketing-observability/stage4-capacity/` 참조.

- backend × 2 (각 2 cpu / 2g) + Nginx LB + Redis (1 cpu) + PostgreSQL (2 cpu)
- 총 ~10 cpu — Mac Docker Desktop 한계 안
- k6 부하 패턴: Stage 3 와 동일 (100 → 5000 RPS ramp)
- 결과: `stage4-capacity/results/stage4-{dual,single}.summary.json`

## Mac 한계 안 제약

- backend × 3 시도 시 자원 빠듯 — × 2 로 진행
- Outbox + Kafka 어려움 → outbox + 폴링 worker (Kafka 없이)
- Redis Cluster 미적용 — 단일 Redis 인스턴스. 운영은 sentinel/cluster 필요

## 잔존 위험

- **Redis 단일 인스턴스 SPOF**: 락 / 큐 / fence 가 모두 한 Redis 에 의존. sentinel/cluster 미적용.
- **fencing token 의 멱등 검증 비용**: 모든 critical UPDATE 에 `:fence > lock_token` 조건 추가. 인덱스 영향 측정 필요.
- **Outbox 적재 폭증 대응 미흡**: 결제 콜백 폭주 시 outbox 폴링이 따라잡지 못하면 lag 증가. Kafka 같은 분리 필요.
- **ShedLock 정확성 한계**: leader 의 GC pause 동안 lockAtMostFor 만료 → 두 인스턴스가 동시 실행 가능. fence 와 같은 자체 보호 필요.
