# 동시성·부하테스트 근거

제출용 PDF의 `Ticketing Concurrency Lab` 섹션에서 `상세 테스트`로 연결되는 근거 문서 인덱스다. PDF는 요약본이고, 이 디렉터리는 테스트 코드, 실행 결과, k6 원본 결과, Grafana/Prometheus 자료를 확인하는 용도다.

## 상세 문서

| PDF 구분 | 상세 문서 | 검증 성격 | 핵심 근거 |
| --- | --- | --- | --- |
| 부하테스트 및 개선 1 | [좌석 예약 경합](seat-reservation-contention.md) | 단위 테스트, 성능 비교 | 동일 좌석 최종 예약 1건, p99 `586ms -> 192ms`, 처리량 `1490 -> 4219 ops/s` |
| 부하테스트 및 개선 2 | [예매 오픈 피크 트래픽](opening-surge-queue.md) | 단위 테스트, k6 | 대기열 미적용 실패 요청 `154,849건`, 메모리 대기열 적용 후 대기 중 타임아웃 `0건` |
| 부하테스트 및 개선 3 | [Redis 기반 멀티 인스턴스 확장](redis-multi-instance.md) | 단위 테스트, k6, Prometheus/Grafana | Redis 상태 공유, Hikari pending, PostgreSQL connection, 전체 p95 비교 |

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

| 구분 | 테스트 코드 | 확인 내용 |
| --- | --- | --- |
| 좌석 예약 경합 | [`SeatLockConcurrencyTest.java`](../../concurrency/src/test/java/com/dongwoo/ticketing/concurrency/SeatLockConcurrencyTest.java) | 동일 좌석 동시 요청에서 최종 예약 1건 유지 |
| 메모리 대기열 | [`QueueLoadTest.java`](../../queue/src/test/java/com/dongwoo/ticketing/QueueLoadTest.java) | 토큰 발급, 순서 일관성, 통과 속도 |
| Redis 대기열 | [`DistributedQueueTest.java`](../../distributed/src/test/java/com/dongwoo/ticketing/DistributedQueueTest.java) | 인스턴스 간 토큰 조회, 중복 통과 방지, FIFO |
| Redis 좌석 락 | [`DistributedSeatLockTest.java`](../../distributed/src/test/java/com/dongwoo/ticketing/DistributedSeatLockTest.java) | 동일 좌석 동시 락 획득 1건 |
| Fencing token | [`FencingTokenTest.java`](../../distributed/src/test/java/com/dongwoo/ticketing/FencingTokenTest.java) | 늦게 도착한 이전 락 보유자의 갱신 차단 |

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
| Stage 3 비교 결과 | [`stage3-capacity/results/comparison.md`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage3-capacity/results/comparison.md) |
| Stage 4 k6 script | [`stage4-capacity/k6/opening-surge.js`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/k6/opening-surge.js) |
| Stage 4 결과 디렉터리 | [`stage4-capacity/results`](https://github.com/dongwooooooo/ticketing-observability/tree/main/stage4-capacity/results) |
| Prometheus 추출값 | [`stage4-prometheus-evidence.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/screenshots/portfolio-evidence/stage4-prometheus-evidence.json) |
| Grafana 캡처 | [`screenshots/portfolio-evidence/selected`](https://github.com/dongwooooooo/ticketing-observability/tree/main/screenshots/portfolio-evidence/selected) |
| 재현성 메모 | [`REPRODUCIBILITY.md`](https://github.com/dongwooooooo/ticketing-observability/blob/main/screenshots/portfolio-evidence/REPRODUCIBILITY.md) |

## 원본 결과 파일

| 구분 | 파일 |
| --- | --- |
| 대기열 미적용 4 CPU / 4 CPU | [`stage2-capacity/results/a-4/summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage2-capacity/results/a-4/summary.json) |
| 메모리 대기열 4 CPU / 4 CPU | [`stage3-capacity/results/a-4/summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage3-capacity/results/a-4/summary.json) |
| Redis 1대 x 2 CPU / pool 10 | [`stage4-single-opening-rerun2-1x2-pool10.summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/results/stage4-single-opening-rerun2-1x2-pool10.summary.json) |
| Redis 1대 x 4 CPU / pool 20 | [`stage4-single-opening-rerun2-1x4-pool20.summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/results/stage4-single-opening-rerun2-1x4-pool20.summary.json) |
| Redis 2대 x 2 CPU / pool 10 | [`stage4-dual-opening-rerun1-2x2-pool10.summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/results/stage4-dual-opening-rerun1-2x2-pool10.summary.json) |
| Redis 2대 x 2 CPU / pool 20 | [`stage4-dual-opening-rerun1-2x2-pool20.summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/results/stage4-dual-opening-rerun1-2x2-pool20.summary.json) |
