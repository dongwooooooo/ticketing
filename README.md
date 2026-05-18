# ticketing

콘서트 티케팅 시스템 — 단일 서버부터 분산 환경까지 점진 구현 시리즈.

각 스테이지는 이전 스테이지에서 발견한 문제를 하나씩 해결하는 **독립 Gradle 모듈**로 같은 레포 안에 둔다.

## 스테이지

| # | 모듈 | 푸는 문제 | 이전 스테이지 한계 | 측정 결과 |
|---|---|---|---|---|
| 1 | [`basic/`](basic/) | 단일 서버 happy path | (시작) | `SeatRaceReproTest`: 좌석 1개에 10명 HELD (oversell=10) |
| 2 | [`concurrency/`](concurrency/) | 좌석 oversell, 결제 멱등성, 만료-결제 race | Stage 1 naive race 발생 | race 차단 ✅ — `SeatLockConcurrencyTest` / `PaymentIdempotencyConcurrencyTest` / `ExpiryPaymentRaceTest` |
| 3 | [`queue/`](queue/) | 50만 동시 접속 대기열, 백엔드 보호 | Stage 2 hot seat p99=586ms, deadlock 98% 가능성 | gate 통과 ✅ — `HappyPathIntegrationTest` 4 PASS, `QueueLoadTest` enqueue 67K ops/s p99=0.059ms |
| 4 | `distributed/` | 다중 인스턴스 분산 락, ShedLock, fencing | Stage 3 단일 JVM 큐 한계 + DB failover 회색지대 (commit 후 ACK 전) | (계획) |

진행 시점에 해당 모듈 디렉토리 생성 + `settings.gradle`에서 include 해제.

## 부하 측정 시나리오 26종

Stage 1/2/3 각 시점에서 **사용자가 어느 시점 어느 화면에서 무엇을 클릭하다 어떤 결과를 보는가** 형식으로 26 시나리오 검증. 18 PASS / 6 OBSERVED (위험 노출) / 2 미측정.

| 분류 | 시나리오 수 | 측정 위치 |
|---|---|---|
| 좌석 락 대안 비교 (alt-A~F + Z 채택) | 7 | [`seat-lock-alternatives`](https://github.com/dongwooooooo/seat-lock-alternatives) |
| 베이스라인 정합성 부하 (Hot seat / Distributed / Pool exhaustion) | 3 | [`stress-baseline`](https://github.com/dongwooooooo/seat-lock-alternatives/tree/main/stress-baseline) |
| 베이스라인 운영 위험 (Deadlock / Lock timeout / Starvation / Rollback / Leak / JPA cache) | 6 | [`stress-baseline-deep`](https://github.com/dongwooooooo/seat-lock-alternatives/tree/main/stress-baseline-deep) |
| 대기열 대안 비교 (Redis ZSET / in-process / PG SKIP LOCKED) | 3 | [`queue-alternatives`](https://github.com/dongwooooooo/queue-alternatives) |
| 사용자 행동 위험 (결제 콜백 폭주 / GC pause / sold-out 봇 / DB master 장애) | 4 | [`concurrency/scenario-{7,9,10,12}-*.txt`](concurrency/) |
| k6 + Grafana 3 stage 시각화 | 3 | [`ticketing-observability`](https://github.com/dongwooooooo/ticketing-observability) |

상세 시나리오 (사용자 행동 / 서버 부위 / 무엇 때문에 / 사용자가 보는 결과 / 측정 수치) 는 **[Notion 부하 측정 시나리오 페이지](https://www.notion.so/36473344235881adbdf7d64842f9a539)** 에서 통합 정리.

Stage 3 진입 실측 근거: [`docs/stage3-entry-rationale.md`](docs/stage3-entry-rationale.md)

## 대전제 (BTS 2026 ARIRANG 기준)

- 좌석 50,000 / 동시 접속 500,000 (10:1 oversubscription)
- Peak ~5,000 TPS / Sustained 500~1,000 TPS
- Mexico City 실측: 좌석 150K vs 대기열 1.1M (7.3:1, 37분 매진)

상세 도메인/스코프: [docs/scope.md](docs/scope.md) (작성 예정)

## 기술 스택 (공통)

- Java 25
- Spring Boot 4.0.0
- PostgreSQL 16
- Flyway / JUnit 5 / Testcontainers
- Stage별 추가: Redis (Stage 2~), Kafka (Stage 3+), ShedLock (Stage 4)

## 빌드

루트 wrapper 공유:

```bash
# 특정 모듈 빌드
./gradlew :basic:build

# 특정 모듈 테스트
./gradlew :basic:test

# 특정 모듈 실행
./gradlew :basic:bootRun
```

각 모듈은 독립 docker-compose + 독립 PostgreSQL 포트 (basic: 5432, concurrency: 5433, queue: 5434, distributed: 5435) — 충돌 없이 병렬 기동 가능.

## 공통 docs/

- `docs/scenarios.md` — **모든 스테이지 공통 시나리오 카탈로그** (User/Multi/Payment/System/Failure 30+ 시나리오, Stage별 책임 매트릭스, 우선순위 P0~P3, 시니어 비판 검토)
- `docs/scope.md` — 전체 대전제, 5만 좌석 산정 근거, BTS 실수치 (작성 예정)
- `docs/system-design.md` — hellointerview 스타일 시스템 디자인 (Stage 2부터 적용)
- `docs/decision-journal/` — 각 deep dive 본인 사고 기록 5블록
- [`docs/stage3-entry-rationale.md`](docs/stage3-entry-rationale.md) — Stage 3 진입 실측 근거 (seat-lock-alternatives stress 결과)

## 관련 레포

- [`seat-lock-alternatives`](https://github.com/dongwooooooo/seat-lock-alternatives) — 좌석 락 6 대안 + stress + deep stress 측정
- [`queue-alternatives`](https://github.com/dongwooooooo/queue-alternatives) — 대기열 3 대안 비교
- [`ticketing-observability`](https://github.com/dongwooooooo/ticketing-observability) — k6 + Prometheus + Grafana 부하 측정 스택 + Stage 1/2/3 Grafana 스크린샷

## 외부 인용 (Stage 2+ 활용)

- 우아한형제들 락 키 설계: https://techblog.woowahan.com/17416/
- 토스페이먼츠 Idempotency-Key: https://docs.tosspayments.com/guides/using-api/idempotency-key
- 포트원 Webhook 사양: https://portone.gitbook.io/docs/result/webhook
- Kleppmann distributed locking: https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- Grafana k6: https://grafana.com/docs/k6/using-k6/scenarios/executors/ramping-arrival-rate/
