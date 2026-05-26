# k6 실측 결과 인덱스

k6로 측정한 부하테스트 결과와 원본 summary 경로다.

## 예매 오픈 피크 트래픽

| 구분 | 원본 summary | 주요 수치 |
| --- | --- | --- |
| 대기열 미적용, 4 CPU / 4 CPU | [`stage2-a4-summary.json`](raw/stage2-a4-summary.json) | 좌석 예약 요청 `204,043건`, 성공 `49,194건`, 실패 요청 `154,849건` |
| 메모리 대기열, 4 CPU / 4 CPU | [`stage3-a4-summary.json`](raw/stage3-a4-summary.json) | 토큰 발급 `106,683건`, 좌석 예약 성공 `44,178건`, 대기 중 타임아웃 `0건`, 대기 시간 p95 `3.6초` |

비교 문서:

- [`stage3-comparison.md`](raw/stage3-comparison.md)

## Redis 기반 멀티 인스턴스 확장

| 구분 | 원본 summary | 토큰 / 통과 / 예약 성공 | 토큰 발급 실패 | 전체 p95 |
| --- | --- | ---: | ---: | ---: |
| 1대 x 2 CPU / pool 10 | [`stage4-single-1x2-pool10-summary.json`](raw/stage4-single-1x2-pool10-summary.json) | 78,407 / 78,375 / 50,000 | 2,551 | 5.16초 |
| 1대 x 4 CPU / pool 20 | [`stage4-single-1x4-pool20-summary.json`](raw/stage4-single-1x4-pool20-summary.json) | 82,488 / 82,488 / 50,000 | 0 | 0.51초 |
| 2대 x 2 CPU / pool 10 | [`stage4-dual-2x2-pool10-summary.json`](raw/stage4-dual-2x2-pool10-summary.json) | 81,394 / 81,394 / 50,000 | 88 | 3.17초 |
| 2대 x 2 CPU / pool 20 | [`stage4-dual-2x2-pool20-summary.json`](raw/stage4-dual-2x2-pool20-summary.json) | 82,445 / 82,445 / 50,000 | 0 | 0.66초 |

Prometheus/Grafana 자료:

- [`stage4-prometheus-evidence.json`](raw/stage4-prometheus-evidence.json)
- [`redis-multi-instance-hikari-active-pending.png`](../assets/redis-multi-instance-hikari-active-pending.png)
- [`redis-multi-instance-redis-db-load.png`](../assets/redis-multi-instance-redis-db-load.png)
