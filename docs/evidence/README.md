# 동시성·부하테스트 근거

Ticketing Concurrency Lab에서 수행한 동시성 검증, 부하테스트, Grafana/Prometheus 관측 자료를 모은 문서 인덱스다. 각 문서는 문제 상황, 테스트 코드, 실행 결과, k6 원본 요약, 관측 캡처를 함께 확인할 수 있도록 구성했다.

## 상세 문서

| 주제 | 상세 문서 | 검증 성격 | 주요 결과 |
| --- | --- | --- | --- |
| 좌석 예약 경합 | [좌석 예약 경합](seat-reservation-contention.md) | 단위 테스트, 성능 비교 | 동일 좌석 최종 예약 1건, p99 `586ms -> 192ms`, 처리량 `1490 -> 4219 ops/s` |
| 예매 오픈 피크 트래픽 | [예매 오픈 피크 트래픽](opening-surge-queue.md) | 단위 테스트, k6 | 대기열 미적용 실패 요청 `181,366건`, 메모리 대기열 적용 후 대기 중 제한 시간 초과 `0건` |
| Redis 기반 멀티 인스턴스 확장 | [Redis 기반 멀티 인스턴스 확장](redis-multi-instance.md) | 단위 테스트, k6, Prometheus/Grafana | Redis 상태 공유, Hikari pending, DB connection, 전체 p95 비교 |

## 공통 검증 환경

| 항목 | 내용 |
| --- | --- |
| 로컬 환경 | Mac M2 Pro 16GB, Docker Desktop |
| 부하테스트 리소스 | Docker 10 CPU / 8GB 범위에서 측정 |
| 애플리케이션 | Java, Spring Boot, PostgreSQL, Redis |
| 부하 도구 | k6 |
| 관측 도구 | Prometheus, Grafana |
| 대표 부하 패턴 | `100 -> 500 -> 1000 -> 2000 -> 3500 -> 5000 RPS`, `600 -> 800 -> 1000 -> 1200 RPS` |
| 제외 범위 | 실제 PG/카드사 호출, 운영 환경 장애 전환, Redis/PostgreSQL 클러스터링과 저장소 계층 확장, 실시간 알림과 장기 모니터링 |

## 단위 테스트 코드

| 구분 | 상세 문서 | 확인 내용 |
| --- | --- | --- |
| 중복 예약 재현 | [중복 예약 재현 테스트](tests/seat-race-repro.md) | 단순 조회 후 저장 방식의 좌석 예약 경합 재현 |
| 좌석 예약 경합 | [좌석 예약 경합 테스트](tests/seat-lock-concurrency.md) | 동일 좌석 동시 요청에서 최종 예약 1건 유지 |
| 메모리 대기열 | [메모리 대기열 단위 테스트](tests/queue-load.md) | 토큰 발급, 순서 일관성, 통과 속도 |
| Redis 분산 상태 | [Redis 분산 상태 단위 테스트](tests/redis-distributed-state.md) | 인스턴스 간 토큰 조회, 중복 통과 방지, FIFO, 좌석 락, fencing token |

## k6 / Grafana 결과 해석

| 구분 | 상세 문서 | 확인 내용 |
| --- | --- | --- |
| 예매 오픈 피크 트래픽 | [예매 오픈 피크 트래픽 k6 테스트](tests/opening-surge-k6.md) | 대기열 미적용과 메모리 대기열 적용 비교, Grafana 결과 해석 |
| Redis 멀티 인스턴스 | [Redis 멀티 인스턴스 k6 테스트](tests/redis-multi-instance-k6.md) | 단일 인스턴스 스펙업과 백엔드 2대 구성 비교, HikariCP/Redis/DB 지표 해석 |

## 실행 결과 로그

| 구분 | 결과 파일 |
| --- | --- |
| 좌석 예약 경합 단위 테스트 | [`results/seat-reservation-unit-test.txt`](results/seat-reservation-unit-test.txt) |
| 메모리 대기열 단위 테스트 | [`results/queue-load-test.txt`](results/queue-load-test.txt) |
| Redis 분산 상태 단위 테스트 | [`results/redis-distributed-unit-tests.txt`](results/redis-distributed-unit-tests.txt) |
| k6 실측 결과 인덱스 | [`results/k6-measurements.md`](results/k6-measurements.md) |

## k6 / 관측 원본

| 구분 | 원본 경로 |
| --- | --- |
| Stage 2 k6 script | [`stage2-capacity/k6/capacity-probe.js`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage2-capacity/k6/capacity-probe.js) |
| Stage 3 k6 script | [`stage3-capacity/k6/capacity-probe.js`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage3-capacity/k6/capacity-probe.js) |
| Stage 4 k6 script | [`stage4-capacity/k6/opening-surge.js`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/k6/opening-surge.js) |
| Prometheus 시계열 | [`results/prometheus-timeseries`](results/prometheus-timeseries/) |
| Grafana 캡처 | [`assets`](assets/) |

## 원본 결과 파일

| 구분 | 파일 |
| --- | --- |
| 대기열 미적용 4 CPU / 4 CPU | [`results/raw/stage2-a4-summary.json`](results/raw/stage2-a4-summary.json) |
| 메모리 대기열 4 CPU / 4 CPU | [`results/raw/stage3-a4-summary.json`](results/raw/stage3-a4-summary.json) |
| Redis 1대 x 2 CPU / pool 10 | [`results/raw/stage4-single-1x2-pool10-summary.json`](results/raw/stage4-single-1x2-pool10-summary.json) |
| Redis 1대 x 4 CPU / pool 20 | [`results/raw/stage4-single-1x4-pool20-summary.json`](results/raw/stage4-single-1x4-pool20-summary.json) |
| Redis 2대 x 2 CPU / pool 10 | [`results/raw/stage4-dual-2x2-pool10-summary.json`](results/raw/stage4-dual-2x2-pool10-summary.json) |
