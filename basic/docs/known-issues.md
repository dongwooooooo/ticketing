# Known Issues (Stage 2~4에서 해결 예정)

본 레포는 단일 서버 happy path 동작만 검증한다. 아래 문제는 **의도적으로** 해결하지 않는다. 각 문제는 다음 스테이지에서 별도 레포로 해결한다.

## I-001 좌석 동시 선점 race

**현상**: 같은 좌석에 동시 100건 예매 요청이 들어오면 naive `findById` → 메모리 검사 → `save` 흐름이 모두 "AVAILABLE"을 읽고 전부 HELD를 기록한다.

**재현**: `SeatRaceReproTest` — `ExecutorService(100)` + `CountDownLatch` 동시 진입 → `SELECT count(*) FROM reservation WHERE seat_id=? AND status='HELD'` 결과가 1보다 큼.

**왜 본 레포에서 안 푸는가**: 동시성 제어는 Stage 2의 핵심 주제. 본 레포가 race를 가지고 있어야 Stage 2의 가치가 측정 가능.

**해결 스테이지**: ticketing-concurrency

## I-002 결제 콜백 중복 처리

**현상**: PG callback이 N회 도착하면 N번 결제 확정. `Idempotency-Key` 처리 없음.

**재현**: `PaymentCallbackDuplicationReproTest` — 같은 paymentId로 callback 10회 전송 → `payment.status='CONFIRMED'` 변경이 10회 모두 발생.

**왜 본 레포에서 안 푸는가**: 멱등성 처리 패턴은 Stage 2에서 비교 구현.

**해결 스테이지**: ticketing-concurrency

## I-003 만료-결제 lost update

**현상**: `ExpiryScheduler`가 만료 처리하는 동시에 PG callback이 도착하면 두 트랜잭션이 같은 row를 건드려 lost update 발생.

**재현**: `ExpiryPaymentRaceReproTest` — 만료 처리와 callback을 동시 진입 → reservation.status가 비결정적.

**왜 본 레포에서 안 푸는가**: atomic UPDATE 패턴은 Stage 2의 deep dive 주제.

**해결 스테이지**: ticketing-concurrency

## I-004 트래픽 폭주 시 백엔드 직격

**현상**: BTS급 동시 접속 500,000 시나리오에서 API 서버가 직접 부하를 받는다. HikariCP pool 고갈, p99 폭증.

**재현**: 본 레포에선 측정만 (k6 시나리오 미포함). Stage 3 도입 근거.

**해결 스테이지**: ticketing-queue

## I-005 만료 스케줄러 다중 인스턴스 중복 실행

**현상**: 본 레포는 단일 인스턴스 가정. 인스턴스 N개로 띄우면 모든 인스턴스의 `@Scheduled`가 동시에 만료 처리 시도.

**재현**: docker-compose로 Spring 인스턴스 2개 띄우고 `@Scheduled` 트리거 → 같은 reservation을 두 인스턴스가 모두 처리 시도.

**해결 스테이지**: ticketing-distributed (ShedLock)

## I-006 분산 환경 좌석 락 안전성

**현상**: 단일 노드 가정에서는 row lock으로 충분. 다중 노드에서 Redis SETNX 만으로는 GC pause / 네트워크 분할 시 zombie lock 발생 (Kleppmann fencing token 부재).

**해결 스테이지**: ticketing-distributed

## I-007 외부 호출 cascade

**현상**: 결제 트랜잭션 안에 PG 외부 호출이 들어가면 timeout 시 락 보유 시간이 길어진다. HikariCP pool 고갈로 같은 좌석 노리는 다른 요청들 대기 후 timeout.

**왜 본 레포에서 안 푸는가**: 본 레포는 PG mock 동기 호출이라 cascade 영향 최소. Stage 2에서 의도적 sleep 주입으로 cascade 재현.

**해결 스테이지**: ticketing-concurrency
