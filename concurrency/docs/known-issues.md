# Known Issues — Stage 2 시점

Stage 1(`basic/`)의 known-issues를 본 Stage에서 해결한 항목과 다음 Stage로 미룬 항목으로 분리.

## ✅ Stage 2에서 해결한 이슈

### I-001 좌석 동시 선점 race — 해결

- 패턴: `@Lock(PESSIMISTIC_WRITE)` + partial UNIQUE index (`status IN ('HELD','PAID')`)
- 검증: `SeatLockConcurrencyTest` — 동시 100건 → HELD 1건
- 상세: [decision-journal/dd1-seat-lock.md](decision-journal/dd1-seat-lock.md)

### I-002 결제 콜백 중복 처리 — 해결

- 패턴: `idempotency_key` UNIQUE + `DataIntegrityViolationException` catch → 기존 응답 replay
- 검증: `PaymentIdempotencyConcurrencyTest` — 동시 100건 → attempt 1건
- 상세: [decision-journal/dd2-idempotency.md](decision-journal/dd2-idempotency.md)

### I-003 만료-결제 lost update — 해결

- 패턴: atomic `UPDATE WHERE status=:expected` (HELD→PAID, HELD→EXPIRED 각각 단일 SQL)
- 검증: `ExpiryPaymentRaceTest` — 만료+callback 동시 → PAID xor EXPIRED
- 상세: [decision-journal/dd3-expiry-race.md](decision-journal/dd3-expiry-race.md)

### I-007 외부 호출 cascade — 부분 해결

- 본 Stage는 트랜잭션 안에 외부 호출이 없도록 분리 (Mock PG는 별도 트랜잭션에서 callback 처리)
- 의도적 sleep 주입으로 cascade 재현은 별도 LockCascadeReproTest로 (선택 과제)

## 🔜 Stage 3 (queue)으로 이월

### I-004 트래픽 폭주 시 backend 직격

- BTS급 동시 접속 500K 시나리오에서 API 서버가 부하 직격 → HikariCP pool 고갈, p99 폭증
- 본 Stage는 race 차단만, 부하 분산은 Stage 3 주제
- Stage 3 도입 트리거: k6로 sustained 200 TPS / peak 5,000 TPS 측정 후 p99 합격선 미달 시

## 🔜 Stage 4 (distributed)로 이월

### I-005 만료 스케줄러 다중 인스턴스 중복 실행

- 본 Stage는 단일 인스턴스 가정. 인스턴스 N개로 띄우면 `@Scheduled`가 모든 인스턴스에서 실행
- Stage 4에서 ShedLock 또는 leader election 도입

### I-006 분산 환경 좌석 락 안전성

- 단일 노드는 row lock으로 충분. 다중 노드에서 Redis SETNX만으로는 GC pause + 네트워크 분할 시 zombie lock
- Kleppmann fencing token 도입 (Stage 4)

## 새로 식별된 이슈 (Stage 2 작업 중 발견)

### I-008 멱등 hit 시 amount 불일치 미검증

- `idempotency-key`만 같고 amount가 다른 요청이 와도 기존 응답 그대로 반환
- 운영에선 `request_hash` 검증 필요 — 본 모듈은 hash 필드만 두고 미검증
- Stage 3에서 보완 (또는 별도 운영-grade 항목으로 격상)

### I-009 환불 큐 미구현

- `affected=0`인 callback은 `log.warn`만 출력
- 운영에선 outbox + 별도 워커 필요
- Stage 4에서 outbox + retry 도입

### I-010 좌석 release 분리 트랜잭션 windowing

- reservation EXPIRED 마킹 후 seat release loop는 별도 트랜잭션
- 두 단계 사이 장애 시 reservation=EXPIRED, seat=BOOKED인 inconsistent state 가능
- 운영에선 outbox + reconciliation worker (Stage 4)
