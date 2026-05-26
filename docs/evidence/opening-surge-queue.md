# 예매 오픈 피크 트래픽

예매 오픈 직후 5K RPS 부하에서 좌석 예약 API 직접 처리와 메모리 기반 대기열 적용 결과를 비교한 문서다. 처리 한도를 넘은 요청이 실패 응답으로 종료되는지, 대기 상태로 유지되는지 확인했다.

## 검증 대상

- 예매 오픈 직후 5K RPS 부하에서 대기열 미적용과 메모리 대기열 적용 결과를 비교한다.
- 처리 한도를 넘은 요청이 실패 응답으로 종료되는지, 대기 상태로 유지되는지 확인한다.
- 대기열 적용 후 사용자 대기 시간이 어느 정도로 나타나는지 확인한다.

## 상세 테스트

| 구분 | 상세 문서 | 확인 내용 |
| --- | --- | --- |
| 메모리 대기열 단위 테스트 | [메모리 대기열 단위 테스트](tests/queue-load.md) | 토큰 발급, 순서 일관성, 통과 속도 |
| 예매 오픈 피크 트래픽 | [예매 오픈 피크 트래픽 k6 테스트](tests/opening-surge-k6.md) | 대기열 미적용과 메모리 대기열 적용 비교, Grafana 결과 해석 |

## 실행 명령

```bash
./gradlew :queue:test --tests '*QueueLoadTest'
```

## 단위 테스트 결과

| 테스트 | 입력 조건 | 결과 |
| --- | --- | --- |
| 토큰 발급 처리량 | 10,000건 동시 발급 | `67,841.6 ops/s`, p99 `0.059ms` |
| 순서 일관성 | 토큰 100개, 각 50회 조회 | 불일치 0건 |
| 통과 속도 유지 | 100ms마다 10건 통과, 10초 | 실제 통과 970건, 평균 `96.61/s` |

```text
uniqueTokens=10000
enqueueOpsPerSec=67841.6
p99EnqueueMs=0.059
inconsistencyCount=0
expectedAdmits=1000
actualAdmits=970
avgAdmitRatePerSec=96.61
```

원본 결과:

- [`queue/queue-load-output.txt`](../../queue/queue-load-output.txt)
- [`results/queue-load-test.txt`](results/queue-load-test.txt)

## k6 부하테스트 조건

| 항목 | 대기열 미적용 | 메모리 대기열 적용 |
| --- | --- | --- |
| 모듈 | `concurrency` | `queue` |
| 부하 패턴 | `100 -> 500 -> 1000 -> 2000 -> 3500 -> 5000 RPS` | `100 -> 500 -> 1000 -> 2000 -> 3500 -> 5000 RPS` |
| 백엔드 / DB | 4 CPU / 4 CPU | 4 CPU / 4 CPU |
| k6 스크립트 | [`stage2-capacity/k6/capacity-probe.js`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage2-capacity/k6/capacity-probe.js) | [`stage3-capacity/k6/capacity-probe.js`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage3-capacity/k6/capacity-probe.js) |
| 원본 결과 | [`results/raw/stage2-a4-summary.json`](results/raw/stage2-a4-summary.json) | [`results/raw/stage3-a4-summary.json`](results/raw/stage3-a4-summary.json) |
| Prometheus 시계열 | [`portfolio-stage2-a4-r2.json`](results/prometheus-timeseries/portfolio-stage2-a4-r2.json) | [`portfolio-stage3-a4-r1.json`](results/prometheus-timeseries/portfolio-stage3-a4-r1.json) |

## k6 결과

| 구성 | 요청/토큰 | 좌석 예약 성공 | 실패 요청 | 대기 중 제한 시간 초과 | 대기 시간 p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 대기열 미적용 | 좌석 예약 요청 `230,858건` | `49,492건` | `181,366건` | 해당 없음 | 해당 없음 |
| 메모리 대기열 적용 | 토큰 발급 `119,683건`, 대기열 통과 `119,683건` | `45,488건` | 좌석 매진 이후 거절 `74,195건` | `0건` | `4.37초` |

Grafana/결과 원본:

- [`results/k6-measurements.md`](results/k6-measurements.md)
- [`results/prometheus-timeseries/portfolio-stage2-a4-r2.json`](results/prometheus-timeseries/portfolio-stage2-a4-r2.json)
- [`results/prometheus-timeseries/portfolio-stage3-a4-r1.json`](results/prometheus-timeseries/portfolio-stage3-a4-r1.json)

![대기열 미적용 실패 요청과 대기열 적용 결과](assets/opening-surge-failed-requests.png)

![대기 시간과 전체 응답시간](assets/opening-surge-wait-total-latency.png)

## 결론

대기열 미적용 부하에서는 처리 한도를 넘은 요청이 실패 응답과 제한 시간 초과로 종료됐다. 메모리 기반 대기열 적용 후에는 같은 4 CPU 조건에서 대기 중 제한 시간 초과가 0건으로 유지됐다. 이 결과를 근거로 피크 요청을 좌석 예약 API 앞에서 바로 처리하지 않고, 대기열에서 순서를 받은 요청만 좌석 예약 API로 전달하는 구조를 선택했다.
