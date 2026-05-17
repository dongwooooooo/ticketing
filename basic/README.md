# basic — Stage 1 of 4

단일 서버 happy path. **race condition을 의도적으로 가지고 있다**. Stage 2(`concurrency/`)에서 해결.

## 도메인

```
Event > Schedule > Section(VIP/R/S/A/STANDING) > Seat(50,000)
                                                       > Reservation > Payment > PaymentAttempt
```

ERD + 플로우 + 상태 전이: [docs/domain.md](docs/domain.md)

## 의도적 결함 (Stage 2~4에서 해결)

| ID | 이슈 | 해결 stage |
|---|---|---|
| I-001 | 좌석 동시 선점 race (naive find→save) | concurrency |
| I-002 | 결제 callback 중복 처리 (멱등성 부재) | concurrency |
| I-003 | 만료-callback lost update | concurrency |
| I-004 | 트래픽 폭주 시 backend 직격 | queue |
| I-005 | 만료 스케줄러 다중 인스턴스 중복 실행 | distributed |
| I-006 | 분산 환경 좌석 락 안전성 | distributed |
| I-007 | 외부 호출 cascade | concurrency |

상세: [docs/known-issues.md](docs/known-issues.md)

## 실행

```bash
# 터미널 1: PostgreSQL (port 5432)
cd /Users/idong-u/d/ticketing/basic && docker-compose up -d

# 터미널 2: 서버 (port 8080, Flyway + 50K seat seed)
cd /Users/idong-u/d/ticketing && ./gradlew :basic:bootRun

# 터미널 3: race 재현
cd /Users/idong-u/d/ticketing/basic
bash scripts/smoke.sh                       # happy path
bash scripts/race-reserve.sh 1 100          # 좌석 1 동시 100건 (oversell 재현)
bash scripts/race-payment.sh 1 idem-X 50    # 같은 idempotency-key 동시 50건
```

## 측정 (포트폴리오 §4 baseline)

```bash
# JUnit으로 race 재현
./gradlew :basic:test --tests "SeatRaceReproTest"
```

예상 결과: `success > 1`, `heldCount > 1` (oversell 발생). Stage 2에서 정확히 1로 수렴.

## 부하 target TPS (Stage 3+ 사용)

[Toss techchat thread](https://techchat.tosspayments.com/m/1496004223027122206) OP 발언 기준:
- Sustained 200 TPS
- Peak 5,000 TPS (오픈 0~10초)

본 모듈은 race repro 목적, 부하 시나리오는 Stage 3.

## 다음 stage 이동 트리거

본 모듈에서 race 재현 측정을 끝낸 뒤 `concurrency/`로 이동.
