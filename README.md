# ticketing

콘서트 티케팅 시스템 — 단일 서버부터 분산 환경까지 점진 구현 시리즈.

각 스테이지는 이전 스테이지에서 발견한 문제를 하나씩 해결하는 **독립 Gradle 모듈**로 같은 레포 안에 둔다.

## 스테이지

| # | 모듈 | 푸는 문제 | 이전 스테이지 한계 |
|---|---|---|---|
| 1 | [`basic/`](basic/) | 단일 서버 happy path | (시작) |
| 2 | `concurrency/` | 좌석 oversell, 결제 멱등성, 만료-결제 race | Stage 1 naive race 발생 |
| 3 | `queue/` | 50만 동시 접속 대기열, 백엔드 보호 | Stage 2 직접 부하 시 폭주 |
| 4 | `distributed/` | 다중 인스턴스 분산 락, ShedLock, fencing | Stage 3 단일 노드 한계 |

진행 시점에 해당 모듈 디렉토리 생성 + `settings.gradle`에서 include 해제.

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

- `docs/scope.md` — 전체 대전제, 5만 좌석 산정 근거, BTS 실수치
- `docs/system-design.md` — hellointerview 스타일 시스템 디자인 (Stage 2부터 적용)
- `docs/decision-journal/` — 각 deep dive 본인 사고 기록 5블록

## 외부 인용 (Stage 2+ 활용)

- 우아한형제들 락 키 설계: https://techblog.woowahan.com/17416/
- 토스페이먼츠 Idempotency-Key: https://docs.tosspayments.com/guides/using-api/idempotency-key
- 포트원 Webhook 사양: https://portone.gitbook.io/docs/result/webhook
- Kleppmann distributed locking: https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- Grafana k6: https://grafana.com/docs/k6/using-k6/scenarios/executors/ramping-arrival-rate/
