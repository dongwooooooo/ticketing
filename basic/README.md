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

## 실행

```bash
# 인프라 (PostgreSQL 5432)
cd ../  # repo root
docker-compose -f basic/docker-compose.yml up -d

# 빌드
./gradlew :basic:build

# 테스트
./gradlew :basic:test

# 실행
./gradlew :basic:bootRun

# race condition 의도적 재현 (Stage 2 진입 근거)
./gradlew :basic:test --tests '*RaceReproTest'
```

## 다음 스테이지로 이동 트리거

본 모듈에서 race condition 재현 측정을 끝낸 뒤, 같은 도메인 모델을 `concurrency/`로 복사 → 동시성 제어 도입.
