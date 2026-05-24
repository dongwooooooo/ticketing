# 측정 결과 — Stage 1 baseline vs Stage 2

실행 환경:
- macOS 25.2 (darwin-aarch64)
- Java 25 (Corretto)
- PostgreSQL 16 (Testcontainers)
- Docker Engine 24.0.5, docker-java client API pin 1.43

실행 명령:
```bash
./gradlew :basic:test
./gradlew :concurrency:test
```

측정 시점: 2026-05-17

## 비교표

| 시나리오 | Stage 1 (basic) | Stage 2 (concurrency) | 합격선 |
|---|---|---|---|
| **SCN-M-02** 좌석 동시 100건 → HELD 1건 | success=10, **heldCount=10** (oversell) | success=1, rejected=99, **held=1** | =1 ✅ |
| **SCN-U-05** 같은 idem-key 동시 100건 → attempt 1건 | (Stage 1 미측정) | success=1, errors=99, **attempts=1** | =1 ✅ |
| **SCN-S-02** 만료+callback 동시 → PAID xor EXPIRED | (Stage 1 lost update 가능, 미측정) | final = **EXPIRED** | PAID 또는 EXPIRED ✅ |

## Stage 1 (basic) 상세

```
testsuite com.dongwoo.ticketing.repro.SeatRaceReproTest
  tests=1, failures=0
  Race result: success=10, failed=90, HELD reservations for seat 100 = 10
```

- 좌석 100번에 동시 100건 reserve 요청
- success=10건 통과 → DB에 HELD reservation 10건 (oversell)
- Stage 2 진입 근거 확보

```
testsuite com.dongwoo.ticketing.HappyPathIntegrationTest
  tests=1, failures=0
  time=6.4s
```

- 예매 → 결제 → callback SUCCESS → 좌석 SOLD 검증

## Stage 2 (concurrency) 상세

### DD-1: SeatLockConcurrencyTest

```
tests=1, failures=0
success=1 rejected=99 held=1
```

- Pessimistic Lock + partial UNIQUE 2-line defense 채택
- 100건 동시 reserve 중 정확히 1건만 통과 (success=1)
- 99건은 즉시 거부 (rejected=99, DB의 HELD count=1)
- Stage 1의 heldCount=10 → Stage 2의 heldCount=1로 race 차단 확인

### DD-2: PaymentIdempotencyConcurrencyTest

```
tests=1, failures=0
success=1 errors=99 attempts=1
```

- UNIQUE constraint + INSERT 실패 catch 채택
- 같은 idempotency-key로 100건 동시 결제 요청
- 1건만 신규 attempt INSERT 성공, 99건은 DataIntegrityViolationException catch → 기존 응답 replay
- PaymentAttempt row 정확히 1건 (멱등성 보장)
- 추가: INSERT ordering으로 race-loser 99건의 orphan payment row 발생 X

### DD-3: ExpiryPaymentRaceTest

```
tests=1, failures=0
final reservation status: EXPIRED
```

- atomic UPDATE WHERE status=expected 채택
- 만료 스케줄러 + callback SUCCESS를 동시 실행
- 두 트랜잭션이 같은 row를 노렸으나 row-level lock + WHERE 조건 평가로 1건만 affected=1
- 본 측정에서는 만료가 먼저 잡음 → status=EXPIRED (callback의 affected=0 → 환불 큐 로그)
- 반대 케이스(callback 먼저) 도 가능: 가설은 "PAID 또는 EXPIRED 중 하나로 수렴"이며 충족
- Stage 1의 lost update 가능성 차단

## 시나리오 catalog 매핑

| Test | Scenario ID | docs |
|---|---|---|
| SeatLockConcurrencyTest | SCN-M-02 | concurrency/docs/deep-dives.md §DD-1 |
| PaymentIdempotencyConcurrencyTest | SCN-U-05 | concurrency/docs/deep-dives.md §DD-2 |
| ExpiryPaymentRaceTest | SCN-S-02 | concurrency/docs/deep-dives.md §DD-3 |
| SeatRaceReproTest | (Stage 1 baseline) | basic/docs/known-issues.md I-001 |
| HappyPathIntegrationTest | smoke | — |

## 측정 한계

- ExpiryPaymentRaceTest는 1회 실행 결과만 기록. 100회 반복 + 분포 측정은 추가 작업 필요.
- 부하 측정 (k6 sustained 200 TPS / peak 5000 TPS)은 Stage 3 진입 시.
- Lock cascade anti-pattern §1.5 의도적 재현 (LockCascadeReproTest)은 미수행 (concurrency/docs/known-issues.md I-011).

## Stage 4 (distributed) — 분산 구성 측정

측정 시점: 2026-05-24

### 정합성 테스트

| 테스트 | 검증 대상 | 결과 |
|---|---|---|
| DistributedSeatLockTest | 같은 좌석 100건 동시 진입 → Redis SETNX 로 1명만 통과 | acquired=1/100, fence 단조 증가 (1→2→3) PASS |
| FencingTokenTest | A 의 stale holder (fence=1) 가 B (fence=2) 의 DB UPDATE 를 덮어쓰지 못함 | casHold(stale=1) affected=0 PASS |
| DistributedQueueTest | Redis ZSET 큐 / Lua atomic admit / 인스턴스 간 state 공유 | 5/5 cases PASS, 100 토큰 동시 admit 시 중복 0건 |
| OutboxReconciliationTest | FOR UPDATE SKIP LOCKED 폴링 / 실패 attempts++ → 10회 시 DEAD | PASS |

총 11 tests, 0 failures (testcontainers postgres:16 + redis:7-alpine).

### 부하 측정 (stage4-capacity)

[`stage4-capacity/`](https://github.com/dongwooooooo/ticketing-observability/tree/main/stage4-capacity)

- 구성: backend × 2 (각 2cpu/2g) + Nginx LB + Redis (1cpu) + PostgreSQL (2cpu) — Mac 10cpu 한계 안
- 부하 패턴: Stage 3 와 동일 (100 → 5000 RPS ramp)
- 산출물: `results/stage4-dual.summary.json` (k6 metric summary), `results/RESULTS.md` (정성 분석)

**실측 값** (2026-05-24):
- http_reqs total: 337,941 / 1,875.52 req/s
- iterations: 101,821 / 565 iters/s (1 iter = token + admit + reserve 3-step)
- token_issued: 72,798 → admitted: 72,798 (admit gate 100% 통과)
- p95 reserve_latency: 2.3 s / p99 total_latency: ~30 s (한계 도달 후 큐 대기 누적)
- http_req_failed: 38.43% (3500~5000 RPS 한계 도달 후 timeout)

**합격 사항**:
- backend × 2 인스턴스가 round-robin 으로 부하 분산
- Redis ZSET 큐가 cross-instance 일관 동작 (admit 상태 모든 인스턴스 공유)
- admit gate ShedLock 으로 중복 admit 0건

**한계**:
- backend 인스턴스당 2cpu 라 Stage 3 단일(4cpu) 절대 비교 의미 제한 — 수평 확장 효과만 정성 확인
- k6 호스트 ephemeral port 고갈 (마지막 stage)
- Failover / Single 모드 측정은 미실행 (시간 부족)

### Mac 한계 안 측정 의의 / 한계

- 의의: backend × 2 가 단일 인스턴스 대비 throughput / 가용성 측면에서 의미 있는 차이를 보이는지 정성적 확인.
- 한계: 인스턴스당 자원이 Stage 3 단일(4cpu) 보다 작은 2cpu — 절대 throughput 비교 의미 제한. **수평 확장 효과** 만 확인.
- 한계: Redis 단일 인스턴스 — sentinel/cluster 미적용. Redis 다운 시 큐/락 전체 정지.
