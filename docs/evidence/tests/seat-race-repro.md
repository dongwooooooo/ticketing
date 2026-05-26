# 중복 예약 재현 테스트

단순 조회 후 저장 방식에서 같은 좌석을 여러 요청이 동시에 예약하는 상황을 재현한 테스트다. 이 테스트는 수정 전 흐름이 경합에 취약한 구조였음을 보여주는 보조 근거로 사용한다.

## 시나리오

- 좌석 1개에 100개 요청을 동시에 보낸다.
- 각 요청은 좌석 조회, 예약 가능 여부 확인, 예약 저장 순서로 실행된다.
- 락이나 DB 제약이 없으면 여러 요청이 같은 좌석을 예약 가능 상태로 읽을 수 있다.

## 테스트 코드

| 항목 | 경로 |
| --- | --- |
| 테스트 | [`SeatRaceReproTest.java`](../../../basic/src/test/java/com/dongwoo/ticketing/repro/SeatRaceReproTest.java) |
| 예약 서비스 | [`ReservationService.java`](../../../basic/src/main/java/com/dongwoo/ticketing/service/ReservationService.java) |

## 실행 명령

```bash
./gradlew :basic:test --tests '*SeatRaceReproTest'
```

## 결과 해석

이 테스트는 좌석 예약 경합을 의도적으로 만들기 위한 수정 전 재현 테스트다. 최종 근거는 [좌석 예약 경합 테스트](seat-lock-concurrency.md)와 상태 조건 기반 UPDATE 성능 비교 결과를 함께 사용한다.
