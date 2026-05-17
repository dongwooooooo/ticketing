# concurrency — Stage 2 of 4

Stage 1(`basic/`)이 의도적으로 남겨둔 race condition 3종을 해결한다. 단일 서버 가정 유지.

## Stage 1 대비 변경점

| 영역 | Stage 1 (basic) | Stage 2 (concurrency) |
|---|---|---|
| 좌석 선점 race | naive `findById` → save (oversell) | `@Lock(PESSIMISTIC_WRITE)` + partial UNIQUE index |
| 결제 idempotency | DB에 그대로 N건 INSERT | `idempotency_key` UNIQUE + `DataIntegrityViolationException` catch |
| 만료-callback race | `setStatus`로 lost update | atomic `UPDATE ... WHERE status=:expected` |

상세 1:1 diff: [docs/changes-from-basic.md](docs/changes-from-basic.md)
ERD + 플로우 + 상태 전이 + 4-stage 진화: [docs/domain.md](docs/domain.md)

## Deep Dives

| # | 주제 | 패턴 | 의사결정 |
|---|---|---|---|
| 1 | 좌석 동시 선점 | Pessimistic Lock + partial UNIQUE (2-line defense) | [dd1-seat-lock.md](docs/decision-journal/dd1-seat-lock.md) |
| 2 | 결제 idempotency | UNIQUE constraint + INSERT 실패 catch | [dd2-idempotency.md](docs/decision-journal/dd2-idempotency.md) |
| 3 | 만료-callback race | atomic UPDATE WHERE status=expected | [dd3-expiry-race.md](docs/decision-journal/dd3-expiry-race.md) |

측정 시나리오 + 합격선: [docs/deep-dives.md](docs/deep-dives.md)

## 의도적으로 안 다루는 것

| 항목 | 이월 stage |
|---|---|
| 트래픽 폭주 시 backend 직격 (대기열) | Stage 3 (queue) |
| 만료 스케줄러 다중 인스턴스 중복 실행 (ShedLock) | Stage 4 (distributed) |
| Redis 분산락 + fencing token | Stage 4 |
| 결제 endpoint 인증 일관성 (X-User-Id 통일) | Stage 4 (IdP 통합 시) |

추가 식별 이슈: [docs/known-issues.md](docs/known-issues.md)

## 실행

```bash
# 터미널 1: PostgreSQL (basic과 분리된 포트 5433)
cd /Users/idong-u/d/ticketing/concurrency && docker-compose up -d

# 터미널 2: 서버 (port 8081)
cd /Users/idong-u/d/ticketing && ./gradlew :concurrency:bootRun
```

## 측정 — JUnit 시나리오 3종

```bash
./gradlew :concurrency:test --tests "*Concurrency*" --tests "*Race*"
```

| Test | 시나리오 | 가설 |
|---|---|---|
| `SeatLockConcurrencyTest` | SCN-M-02 | 좌석 1에 동시 100건 → HELD 정확히 1건 |
| `PaymentIdempotencyConcurrencyTest` | SCN-U-05 | 같은 key로 동시 100건 → attempt row 1건 |
| `ExpiryPaymentRaceTest` | SCN-S-02 | 만료+callback 동시 → PAID xor EXPIRED |

본 Stage는 race 발생/차단 binary 검증. 부하 측정은 Stage 3 (k6).

## 락 회피 정책

Pessimistic Lock을 명시적으로 도입하되 최소 범위로:

- 트랜잭션 안에 외부 호출 금지 (callback은 별도 트랜잭션)
- `findByIdForUpdate`는 seat 단일 row만, reservation은 락 없이 atomic UPDATE
- Idempotency는 락 대신 UNIQUE constraint (lock-free)

상세 anti-pattern 검토: `resume/ahnlab-ai-service-portfolio/12-lock-antipatterns-and-decision-frame.md`

## 포트폴리오 작성 방향 (메모)

본 모듈은 PDF 포트폴리오의 §5~7 (Stage 2 deep dive 3종)의 소스. 포폴 본문은:

- 문제 → 대안 (5-block decision journal 표) → 채택 → 측정 수치 narrative
- 코드는 GitHub commit URL로 1줄 링크 (본문에 긴 snippet 첨부 X)
- 5-block 표가 핵심 자료

## 다음 Stage

Stage 2 측정 결과 확보 후 Stage 3 (`queue/`) — 대기열 + k6 sustained 200 TPS / peak 5,000 TPS.
