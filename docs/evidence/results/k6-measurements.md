# k6 실측 결과 인덱스

k6로 측정한 부하테스트 결과와 원본 파일 경로입니다.

## 예매 오픈 피크 트래픽

| 구분 | 원본 summary | Prometheus 시계열 | 주요 수치 |
| --- | --- | --- | --- |
| 대기열 미적용, 4 CPU / 4 CPU | [`stage2-a4-summary.json`](raw/stage2-a4-summary.json) | [`portfolio-stage2-a4-r2.json`](prometheus-timeseries/portfolio-stage2-a4-r2.json) | 좌석 예약 성공 `49,492건`, 실패 요청 `181,366건`, reserve p95 `2.61초` |
| 메모리 대기열, 4 CPU / 4 CPU | [`stage3-a4-summary.json`](raw/stage3-a4-summary.json) | [`portfolio-stage3-a4-r1.json`](prometheus-timeseries/portfolio-stage3-a4-r1.json) | 토큰 발급 `119,683건`, 대기열 통과 `119,683건`, 대기 중 제한 시간 초과 `0건`, 대기 시간 p95 `4.37초` |

## Redis 기반 멀티 인스턴스 확장

| 구분 | 원본 summary | Prometheus 시계열 | 토큰 / 통과 / 예약 성공 | 토큰 발급 실패 | 전체 p95 |
| --- | --- | --- | ---: | ---: | ---: |
| 1대 x 2 CPU / pool 10 | [`stage4-single-1x2-pool10-summary.json`](raw/stage4-single-1x2-pool10-summary.json) | [`stage4-single-opening-portfolio-single-1x2-pool10-r2.json`](prometheus-timeseries/stage4-single-opening-portfolio-single-1x2-pool10-r2.json) | 66,467 / 65,085 / 49,349 | 12,490 | 9.63초 |
| 1대 x 4 CPU / pool 20 | [`stage4-single-1x4-pool20-summary.json`](raw/stage4-single-1x4-pool20-summary.json) | [`stage4-single-opening-portfolio-single-1x4-pool20-r1.json`](prometheus-timeseries/stage4-single-opening-portfolio-single-1x4-pool20-r1.json) | 82,487 / 82,487 / 50,000 | 0 | 0.49초 |
| 2대 x 2 CPU / pool 10 x 2 | [`stage4-dual-2x2-pool10-summary.json`](raw/stage4-dual-2x2-pool10-summary.json) | [`stage4-dual-opening-portfolio-dual-2x2-pool10-r1.json`](prometheus-timeseries/stage4-dual-opening-portfolio-dual-2x2-pool10-r1.json) | 79,441 / 79,441 / 50,000 | 1,394 | 4.90초 |

## Grafana 캡처

Grafana 캡처는 화면 설명용으로 사용합니다. 수치는 summary JSON과 Prometheus 시계열 JSON을 기준으로 작성합니다.

- [`redis-multi-instance-hikari-active-pending.png`](../assets/redis-multi-instance-hikari-active-pending.png)
- [`redis-multi-instance-redis-db-load.png`](../assets/redis-multi-instance-redis-db-load.png)
