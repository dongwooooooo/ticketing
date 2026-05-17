# concurrency — Stage 2 of 4

Stage 1(`basic/`)이 의도적으로 남겨둔 race condition 3종을 해결한다. 단일 서버 가정 유지.

## Stage 1 대비 변경점

| 영역 | Stage 1 (basic) | Stage 2 (concurrency) |
|---|---|---|
| 좌석 선점 race | naive `findById` → save (oversell 발생) | `@Lock(PESSIMISTIC_WRITE)` + partial UNIQUE index |
| 결제 idempotency | DB에 그대로 N건 INSERT | `idempotency_key` UNIQUE + `DataIntegrityViolationException` catch |
| 만료-callback race | `setStatus`로 lost update | atomic `UPDATE ... WHERE status=:expected` (affected rows == 1만 채택) |
| 좌석 release | 트랜잭션 안 sequential save | `findByIdForUpdate`로 row lock |

상세: [docs/changes-from-basic.md](docs/changes-from-basic.md)

## Deep Dives (3종)

| # | 주제 | 패턴 | 의사결정 기록 |
|---|---|---|---|
| 1 | 좌석 동시 선점 | Pessimistic Lock + partial UNIQUE index (2-line defense) | [decision-journal/dd1-seat-lock.md](docs/decision-journal/dd1-seat-lock.md) |
| 2 | 결제 idempotency | UNIQUE constraint + INSERT 실패 catch | [decision-journal/dd2-idempotency.md](docs/decision-journal/dd2-idempotency.md) |
| 3 | 만료-callback race | atomic UPDATE WHERE status=expected | [decision-journal/dd3-expiry-race.md](docs/decision-journal/dd3-expiry-race.md) |

상세 측정 계획: [docs/deep-dives.md](docs/deep-dives.md)

## 의도적으로 안 다루는 것 (Stage 3+로 이월)

- 트래픽 폭주 시 backend 직격 (대기열) → `queue/`
- 만료 스케줄러 다중 인스턴스 중복 실행 (ShedLock) → `distributed/`
- Redis 분산락 + fencing token (Kleppmann) → `distributed/`

## 실행 + 검증

```bash
# 터미널 1: PostgreSQL (basic과 다른 포트 5433)
cd /Users/idong-u/d/ticketing/concurrency && docker-compose up -d

# 터미널 2: 서버 (port 8081)
cd /Users/idong-u/d/ticketing && ./gradlew :concurrency:bootRun

# 터미널 3: 동시성 테스트
cd /Users/idong-u/d/ticketing
./gradlew :concurrency:test --tests "*Concurrency*" --tests "*Race*"
```

JUnit 시나리오 (3종):

| Test | 시나리오 | 가설 |
|---|---|---|
| `SeatLockConcurrencyTest` | SCN-M-02 | 좌석 1에 동시 100건 → HELD 정확히 1건 |
| `PaymentIdempotencyConcurrencyTest` | SCN-U-05 | 같은 key로 동시 100건 → attempt row 1건 |
| `ExpiryPaymentRaceTest` | SCN-S-02 | 만료+callback 동시 → PAID xor EXPIRED |

## 락 회피 정책

본 모듈은 Pessimistic Lock을 명시적으로 도입한다. 단, 락은 최소 범위로 제한:

- 트랜잭션 안에 외부 호출 금지 (PG callback은 별도 트랜잭션에서 atomic UPDATE)
- `findByIdForUpdate`는 seat 단일 row만, 결제 row는 락 없이 atomic UPDATE로 처리
- Idempotency는 락 대신 UNIQUE constraint (락-free)

상세 anti-pattern 검토: `resume/ahnlab-ai-service-portfolio/12-lock-antipatterns-and-decision-frame.md`

## 다음 스테이지로 이동 트리거

- Stage 2 측정 끝나면 → Stage 3 (`queue/`): 대기열로 backend 보호, sustained 200 TPS / peak 5,000 TPS 검증
