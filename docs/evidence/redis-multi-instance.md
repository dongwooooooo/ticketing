# Redis 기반 멀티 인스턴스 확장

PDF의 `부하테스트 및 개선 3. Redis 기반 멀티 인스턴스 확장` 상세 근거다.

## 테스트 목적

- 메모리 기반 대기열의 단일 인스턴스 한계를 확인한다.
- Redis 기반 대기열과 좌석 락이 여러 백엔드 인스턴스에서 같은 상태를 공유하는지 확인한다.
- 단일 인스턴스 스펙업과 멀티 인스턴스 구성의 응답시간, HikariCP pending, DB connection을 비교한다.

## 테스트 코드

| 구분 | 경로 | 확인 내용 |
| --- | --- | --- |
| Redis 대기열 | [`distributed/src/test/java/com/dongwoo/ticketing/DistributedQueueTest.java`](../../distributed/src/test/java/com/dongwoo/ticketing/DistributedQueueTest.java) | 인스턴스 간 토큰 조회, 중복 통과 방지, FIFO |
| Redis 좌석 락 | [`distributed/src/test/java/com/dongwoo/ticketing/DistributedSeatLockTest.java`](../../distributed/src/test/java/com/dongwoo/ticketing/DistributedSeatLockTest.java) | 동일 좌석 동시 락 획득 1건 |
| Fencing token | [`distributed/src/test/java/com/dongwoo/ticketing/FencingTokenTest.java`](../../distributed/src/test/java/com/dongwoo/ticketing/FencingTokenTest.java) | 늦게 도착한 이전 락 보유자의 갱신 차단 |
| Redis 대기열 구현 | [`distributed/src/main/java/com/dongwoo/ticketing/queue/RedisWaitingQueue.java`](../../distributed/src/main/java/com/dongwoo/ticketing/queue/RedisWaitingQueue.java) | Redis Sorted Set 기반 대기열 |
| Redis 좌석 락 구현 | [`distributed/src/main/java/com/dongwoo/ticketing/lock/DistributedSeatLock.java`](../../distributed/src/main/java/com/dongwoo/ticketing/lock/DistributedSeatLock.java) | SET NX / TTL 기반 좌석 락 |
| 예약 서비스 | [`distributed/src/main/java/com/dongwoo/ticketing/service/ReservationService.java`](../../distributed/src/main/java/com/dongwoo/ticketing/service/ReservationService.java) | 좌석 락, fencing token, DB 갱신 조건 적용 |

## 실행 명령

```bash
./gradlew :distributed:test --tests '*DistributedQueueTest'
./gradlew :distributed:test --tests '*DistributedSeatLockTest'
./gradlew :distributed:test --tests '*FencingTokenTest'
```

## 단위 테스트 결과

| 테스트 | 입력 조건 | 결과 |
| --- | --- | --- |
| 인스턴스 간 토큰 조회 | 한 인스턴스에서 토큰 발급 후 다른 인스턴스에서 조회 | 조회 가능 |
| 중복 통과 방지 | 2개 인스턴스가 각각 50건씩 동시 처리 | 총 통과 100건, 중복 0건 |
| 대기 순서 유지 | 30건 등록 후 10건 통과 | 통과 10건, 대기 20건 |
| 좌석 락 경합 | 100개 스레드가 같은 좌석 락 획득 시도 | 획득 1건, 거절 99건 |
| fencing token | 이전 락 보유자와 새 락 보유자의 갱신 경쟁 | 이전 보유자 갱신 0건, 새 보유자 갱신 1건 |

```text
distributedQueueCrossInstance tokenVisible=true admittedOnOtherInstance=true
distributedQueueConcurrent instances=2 admitPerCall=50 totalAdmitted=100 admittedCount=100 waitingCount=0 duplicateAdmit=0
distributedQueueFifo enqueued=30 admitted=10 admittedCount=10 waitingCount=20
distributedSeatLockContention threads=100 acquired=1 rejected=99 fence=1
fencingTokenRace fenceA=1 fenceB=2 affectedA=0 affectedB=1 dbLockToken=2 finalStatus=HELD
```

원본 결과:

- [`results/redis-distributed-unit-tests.txt`](results/redis-distributed-unit-tests.txt)
- [`03-redis-distributed-state-gradle-report.png`](https://github.com/dongwooooooo/ticketing-observability/blob/main/screenshots/portfolio-evidence/selected/03-redis-distributed-state-gradle-report.png)
- [`targeted-tests.log`](https://github.com/dongwooooooo/ticketing-observability/blob/main/screenshots/portfolio-evidence/targeted-tests.log)

## k6 부하테스트 조건

| 항목 | 내용 |
| --- | --- |
| 부하 패턴 | `600 -> 800 -> 1000 -> 1200 RPS` |
| k6 스크립트 | [`stage4-capacity/k6/opening-surge.js`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/k6/opening-surge.js) |
| 관측 자료 | [`stage4-prometheus-evidence.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/screenshots/portfolio-evidence/stage4-prometheus-evidence.json) |
| Grafana 캡처 | [`03-redis-multi-instance-hikari-active-pending.png`](https://github.com/dongwooooooo/ticketing-observability/blob/main/screenshots/portfolio-evidence/selected/03-redis-multi-instance-hikari-active-pending.png), [`03-redis-multi-instance-redis-postgres-load.png`](https://github.com/dongwooooooo/ticketing-observability/blob/main/screenshots/portfolio-evidence/selected/03-redis-multi-instance-redis-postgres-load.png) |

## k6 / Prometheus 결과

| 백엔드 구성 | HikariCP pool | 토큰 / 통과 / 예약 성공 | 토큰 발급 실패 | Hikari pending max | PostgreSQL conn max | 전체 p95 |
| --- | ---: | ---: | ---: | --- | ---: | ---: |
| 1대 x 2 CPU | 10 | 78,407 / 78,375 / 50,000 | 2,551 | app1 186 | 13 | 5.16초 |
| 1대 x 4 CPU | 20 | 82,488 / 82,488 / 50,000 | 0 | app1 7 | 22 | 0.51초 |
| 2대 x 2 CPU | 10 x 2 | 81,394 / 81,394 / 50,000 | 88 | app1 170, app2 54 | 23 | 3.17초 |
| 2대 x 2 CPU | 20 x 2 | 82,445 / 82,445 / 50,000 | 0 | app1 72, app2 124 | 42 | 0.66초 |

원본 summary:

- [`results/k6-measurements.md`](results/k6-measurements.md)
- [`stage4-single-opening-rerun2-1x2-pool10.summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/results/stage4-single-opening-rerun2-1x2-pool10.summary.json)
- [`stage4-single-opening-rerun2-1x4-pool20.summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/results/stage4-single-opening-rerun2-1x4-pool20.summary.json)
- [`stage4-dual-opening-rerun1-2x2-pool10.summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/results/stage4-dual-opening-rerun1-2x2-pool10.summary.json)
- [`stage4-dual-opening-rerun1-2x2-pool20.summary.json`](https://github.com/dongwooooooo/ticketing-observability/blob/main/stage4-capacity/results/stage4-dual-opening-rerun1-2x2-pool20.summary.json)

## 해석

`1대 x 4 CPU / pool 20`은 전체 p95 `0.51초`로 가장 낮았다. 로컬 테스트의 응답시간만 보면 단일 인스턴스 스펙업이 유리했다.

`2대 x 2 CPU / pool 20`은 전체 p95 `0.66초`, 토큰 발급 실패 `0건`을 기록했다. 같은 멀티 인스턴스 구성에서도 pool 10에서는 Hikari pending이 커지고 토큰 발급 실패가 발생했으므로, 서버 수뿐 아니라 DB connection 설정도 함께 봐야 했다.

멀티 인스턴스 구조를 선택한 이유는 단순 응답시간 우위가 아니다. 티켓팅 서버는 예매 오픈 직후 장애가 곧바로 운영 리스크로 이어지고, 실제 오픈 시점에는 홍보, 팬덤 유입, 봇 요청, 재시도 요청으로 예측보다 큰 트래픽이 들어올 수 있다. 따라서 여러 백엔드 인스턴스가 같은 대기열과 좌석 락 상태를 공유할 수 있도록 Redis 기반 구조를 사용했다.

서버를 늘리면 DB connection, Redis 요청량, 네트워크, 모니터링, 장애 대응 비용도 함께 증가한다. 이 테스트는 Redis/PostgreSQL 클러스터링이나 저장소 계층 확장까지 다루지는 않았고, 로컬 환경에서 멀티 인스턴스 상태 공유와 부하 지표를 확인한 범위로 제한한다.
