# 좌석 예약 경합

PDF의 `부하테스트 및 개선 1. 좌석 예약 경합` 상세 근거다.

## 테스트 목적

- 동일 좌석에 여러 예약 요청이 동시에 들어와도 최종 예약은 1건만 생성되어야 한다.
- 비관적 락과 상태 조건 기반 UPDATE의 응답시간과 처리량을 비교한다.
- 예약 테이블의 조건부 유니크 인덱스가 중복 예약을 한 번 더 차단하는지 확인한다.

## 테스트 코드

| 구분 | 경로 | 확인 내용 |
| --- | --- | --- |
| 중복 예약 재현 | [`basic/src/test/java/com/dongwoo/ticketing/repro/SeatRaceReproTest.java`](../../basic/src/test/java/com/dongwoo/ticketing/repro/SeatRaceReproTest.java) | 단순 조회 후 저장 방식에서 같은 좌석을 여러 요청이 예약할 수 있는지 확인 |
| 좌석 예약 경합 | [`concurrency/src/test/java/com/dongwoo/ticketing/concurrency/SeatLockConcurrencyTest.java`](../../concurrency/src/test/java/com/dongwoo/ticketing/concurrency/SeatLockConcurrencyTest.java) | 동일 좌석 동시 요청에서 최종 예약 1건 유지 |
| 예약 서비스 | [`concurrency/src/main/java/com/dongwoo/ticketing/service/ReservationService.java`](../../concurrency/src/main/java/com/dongwoo/ticketing/service/ReservationService.java) | 좌석 상태 변경과 예약 생성 흐름 |
| DB 제약 | [`concurrency/src/main/resources/db/migration/V3__concurrency_constraints.sql`](../../concurrency/src/main/resources/db/migration/V3__concurrency_constraints.sql) | 예약 테이블 조건부 유니크 인덱스 |

## 실행 명령

```bash
./gradlew :basic:test --tests '*SeatRaceReproTest'
./gradlew :concurrency:test --tests '*SeatLockConcurrencyTest'
```

## 단위 테스트 결과

| 테스트 | 입력 조건 | 결과 |
| --- | --- | --- |
| `SeatLockConcurrencyTest` | 동일 좌석 1개, 동시 요청 100건 | 성공 1건, 거절 99건, 최종 예약 1건 |

```text
seatReservationRace total=100 success=1 rejected=99 heldCount=1
```

원본 결과:

- [`results/seat-reservation-unit-test.txt`](results/seat-reservation-unit-test.txt)
- [`01-seat-reservation-race-gradle-report.png`](https://github.com/dongwooooooo/ticketing-observability/blob/main/screenshots/portfolio-evidence/selected/01-seat-reservation-race-gradle-report.png)

## 성능 비교 결과

아래 표는 별도 측정 레포의 `stress-baseline`과 상태 조건 기반 UPDATE 측정 결과를 PDF에 사용한 수치다. 단위 테스트는 정합성 확인, 이 표는 응답시간과 처리량 비교에 사용했다.

| 시나리오 | 좌석 조건 | 요청 수 | 예약 방식 | 최종 예약 | 거절 | p99 | 처리량 |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: |
| 동일 좌석 경합 | 좌석 1개 | 1,000건 | 비관적 락과 조건부 유니크 인덱스 | 1 | 999 | 586ms | 1490 ops/s |
| 동일 좌석 경합 | 좌석 1개 | 1,000건 | 상태 조건 기반 UPDATE와 조건부 유니크 인덱스 | 1 | 999 | 192ms | 4219 ops/s |
| 분산 좌석 요청 | 좌석 1,000개 | 2,000건 | 비관적 락 | 808 | 1192 | 1240ms | 1385 ops/s |
| 분산 좌석 요청 | 좌석 1,000개 | 2,000건 | 상태 조건 기반 UPDATE | 808 | 1192 | 782ms | 2250 ops/s |

원본 근거:

- [`docs/stage3-entry-rationale.md`](../stage3-entry-rationale.md)
- [`seat-lock-alternatives`](https://github.com/dongwooooooo/seat-lock-alternatives)

## 결론

비관적 락은 동일 좌석의 최종 예약 1건을 보장했지만, 경합 구간에서 락 대기 시간이 커졌다. 상태 조건 기반 UPDATE와 조건부 유니크 인덱스를 함께 적용한 방식은 같은 정합성 조건을 유지하면서 p99와 처리량이 개선됐다.
