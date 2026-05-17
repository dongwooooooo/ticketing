# basic — Stage 1 of 4

단일 서버, happy path만. 동시성 제어 없음 (의도적).

## 위치

`ticketing/basic/` (Gradle 모듈)

## 다루는 것

- 좌석 50,000 (BTS 잠실주경기장급) seed
- 단일 Spring Boot + 단일 PostgreSQL
- 좌석 조회 / 예매(HOLD) / 결제 / callback / 자가 취소 / 만료

## 의도적으로 안 다루는 것

본 모듈은 race condition을 **의도적으로 가지고 있다**.

상세: [docs/known-issues.md](docs/known-issues.md)

해결은 `concurrency/`, `queue/`, `distributed/` 모듈에서.

## 도메인

```
Event > Schedule(1회차) > Section(VIP/R/S/A/스탠딩) > Seat(50,000)
                                                          > Reservation > Payment > PaymentAttempt
```

상세: [docs/domain-model.md](docs/domain-model.md)

## 실행 + 검증

상세 터미널 명령어: [docs/run.md](docs/run.md)

빠른 시작 (3개 터미널):

```bash
# 터미널 1: PostgreSQL
cd /Users/idong-u/d/ticketing/basic && docker-compose up -d

# 터미널 2: 서버 (Flyway 마이그레이션 자동 실행 + 좌석 50K seed)
cd /Users/idong-u/d/ticketing && ./gradlew :basic:bootRun

# 터미널 3: 검증
cd /Users/idong-u/d/ticketing/basic
bash scripts/smoke.sh                   # happy path 자동 테스트
bash scripts/race-reserve.sh 1 100      # 좌석 1에 동시 100건 예매 (oversell 재현)
bash scripts/race-payment.sh 1 idem-X 50   # 같은 idempotency-key로 동시 50건 결제
```

상세 시나리오 9가지: [docs/run.md](docs/run.md). 만료 분기, 자가 취소, PG callback 중복, 결과 일관성 검증 쿼리 포함.

## 부하 target TPS (Stage 3+에서 활용)

토스 개발자 thread (https://techchat.tosspayments.com/m/1496004223027122206) baseline:

| 시나리오 | TPS |
|---|---|
| 일반 이벤트 sustained | **10 ~ 200 TPS** |
| 대규모 이벤트 peak (드물게) | **수천 TPS** 순간 집중 |

본 Lab 대전제 (BTS 50K 좌석 + 동시 접속 500K + 매진 60분):
- Sustained 200 TPS
- Peak 5,000 TPS (오픈 0~10초)

본 모듈은 race repro 목적이라 동시 100~1,000만 측정. 실제 부하 시나리오는 Stage 3 (queue) — [docs/payment-testing.md](docs/payment-testing.md) §"부하 target TPS".

## 다음 스테이지로 이동 트리거

본 모듈에서 race condition 재현 측정을 끝낸 뒤, 같은 도메인 모델을 `concurrency/`로 복사 → 동시성 제어 도입.
