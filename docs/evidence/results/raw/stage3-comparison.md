# Stage 2 vs Stage 3 비교 결과

대기열 미적용 구성과 메모리 기반 대기열 구성을 같은 4 CPU / 4 CPU 조건에서 비교한 결과입니다.

## 측정 환경

| 항목 | 내용 |
| --- | --- |
| 로컬 환경 | Mac M2 Pro 16GB, Docker Desktop |
| 부하 도구 | k6 |
| 부하 패턴 | `100 -> 500 -> 1000 -> 2000 -> 3500 -> 5000 RPS` |
| 좌석 수 | 50,000개 |
| Stage 2 | 좌석 예약 API 직접 호출 |
| Stage 3 | 토큰 발급, 대기 상태 조회, 좌석 예약 순서로 호출 |

## 결과

| 구성 | 백엔드 / DB CPU | 요청/토큰 | 좌석 예약 성공 | 실패 요청 | 대기 중 제한 시간 초과 | 대기 시간 p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Stage 2 대기열 미적용 | 4 / 4 | 좌석 예약 요청 230,858건 | 49,492건 | 181,366건 | 해당 없음 | 해당 없음 |
| Stage 3 메모리 대기열 | 4 / 4 | 토큰 발급 119,683건, 대기열 통과 119,683건 | 45,488건 | 좌석 매진 이후 거절 74,195건 | 0건 | 4.37초 |

## 원본

- [`stage2-a4-summary.json`](stage2-a4-summary.json)
- [`stage3-a4-summary.json`](stage3-a4-summary.json)
- [`../prometheus-timeseries/portfolio-stage2-a4-r2.json`](../prometheus-timeseries/portfolio-stage2-a4-r2.json)
- [`../prometheus-timeseries/portfolio-stage3-a4-r1.json`](../prometheus-timeseries/portfolio-stage3-a4-r1.json)

## 해석

대기열 미적용 구성에서는 처리 한도를 넘은 요청이 실패 응답으로 종료됐습니다. 메모리 대기열 구성에서는 요청을 먼저 대기열에 받고, 통과한 요청만 좌석 예약 API로 전달했습니다. 이 구간의 핵심 지표는 처리량 증가가 아니라 대기 중 제한 시간 초과가 0건으로 유지됐다는 점입니다.
