# 메모리 대기열 단위 테스트

메모리 기반 대기열이 토큰 발급, 대기 순서 조회, 통과 속도를 유지하는지 확인한 테스트다.

## 시나리오

- 10,000건 토큰 발급을 동시에 실행한다.
- 같은 토큰의 대기 순서 조회 결과가 역행하지 않는지 확인한다.
- 100ms마다 10건씩 통과시키며 통과 속도가 유지되는지 확인한다.

## 테스트 코드

| 항목 | 경로 |
| --- | --- |
| 테스트 | [`QueueLoadTest.java`](../../../queue/src/test/java/com/dongwoo/ticketing/QueueLoadTest.java) |
| 대기열 구현 | [`InProcessWaitingQueue.java`](../../../queue/src/main/java/com/dongwoo/ticketing/queue/InProcessWaitingQueue.java) |
| 대기열 통과 처리 | [`WaitingQueueDispatcher.java`](../../../queue/src/main/java/com/dongwoo/ticketing/queue/WaitingQueueDispatcher.java) |

## 실행 명령

```bash
./gradlew :queue:test --tests '*QueueLoadTest'
```

## 실행 결과

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
actualAdmits=970
avgAdmitRatePerSec=96.61
```

## 결과 해석

메모리 대기열은 단일 인스턴스에서 토큰 발급과 통과 순서 제어를 빠르게 검증하기에 충분했다. 다만 대기열 상태가 인스턴스 메모리에 있으므로 백엔드를 여러 대로 늘리는 구간에서는 Redis 기반 구조가 필요하다.
