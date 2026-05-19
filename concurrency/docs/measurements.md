# Concurrency 모듈 측정 기록

각 시나리오별 raw output 은 `concurrency/scenario-{7,9,10,12}-output.txt` 에 누적.
이 문서는 시점별 요약과 락 전략 변경 전후 비교만 보존.

## 2026-05-19 CAS 재측정

commit 2035d6b (`refactor(concurrency): replace pessimistic lock with CAS in reserve()`) 직후 4 시나리오 재측정.

| 시나리오 | 비관적 락 (2026-05-18) | CAS (2026-05-19) | 변화 |
|---|---|---|---|
| #7 SimpleAsync peak thread | 1016 | 1016 | 동일 |
| #7 SimpleAsync heap MB | 200 | 202 | 동일 |
| #7 SimpleAsync wall ms | 899 | 991 | +10% |
| #7 SimpleAsync PAID | 1000/1000 | 1000/1000 | 동일 |
| #7 ThreadPool peak thread | 66 | 66 | 동일 |
| #7 ThreadPool heap MB | 86 | 86 | 동일 |
| #7 ThreadPool wall ms | 369 | 492 | +33% |
| #7 ThreadPool PAID | 550/1000 | 563/1000 | +13건 |
| #9 winner duration (pause=500) | 313ms | 226ms | -28% |
| #9 winner duration (pause=50) | 257ms | 226ms | -12% |
| #9 contender p99 (pause=500) | 411ms | 235ms | -43% |
| #9 contender p99 (pause=50) | 330ms | 236ms | -29% |
| #10 baseline 봇 throughput | 5890 ops/s | 6405 ops/s | +9% |
| #10 baseline 정상 p99 | 39ms | 54ms | +38% |
| #10 fast-path 봇 throughput | 218892 ops/s | 226725 ops/s | +4% |
| #10 fast-path 정상 p99 | 9ms | 7ms | -22% |
| #10 speedup (baseline/fast-path) | x4.33 | x7.71 | +78% |
| #12 thread A 결과 | COMMIT_FAILED | COMMIT_FAILED | 동일 |
| #12 retry 결과 | success | success | 동일 |
| #12 seat 최종 status | HELD | HELD | 동일 |

### 시나리오별 영향 분류

| 영향 | 시나리오 | 근거 |
|---|---|---|
| 큰 변화 | #9 GC pause | contender p99 -43%/-29%. CAS contender 가 lock queue 가 아닌 application fail-fast 경로로 빠지면서 winner GC pause 와 결합도 사라짐. |
| 양면 변화 | #10 봇 트래픽 | baseline 정상 p99 +38% (악화) vs fast-path 정상 p99 -22% (개선). CAS 가 봇 거절 속도를 올려 DB pool pending 큐가 커진 부작용 + fast-path 통과 시 짧은 CAS UPDATE 로 정상 사용자 단축. |
| 미미한 변화 | #7 콜백 burst | peak thread / heap 동일. wall time +10~33% (CAS UPDATE 직렬화 잔재). PAID 전이 ThreadPool 모드에서 +13건. |
| 영향 없음 | #12 DB failover | 락 전략과 직교. PG 트랜잭션 원자성이 결과를 결정. |

### 핵심 통찰

- **GC pause 시나리오는 CAS 가 직접 해결**. 비관적 락에서 락 holder 의 GC pause = 대기자 timeout 연장 등식이 깨짐. DDIA §8 fencing token 의 단일 노드 변형 문제 완화.
- **봇 트래픽 시나리오는 CAS 와 fast-path 조합이 결정 변수**. CAS 만으로는 baseline 정상 사용자 응답이 오히려 악화 — DB pool 한계가 결정 변수라서 봇 거절 속도가 올라가면 정상 사용자 큐 대기가 길어짐. SoldOutCache fast-path 와 결합하면 speedup x7.71 까지 증가.
- **콜백 burst 와 DB failover 는 락 전략과 종속도 낮음**. 콜백은 ThreadPool 설정, failover 는 PG 트랜잭션 원자성이 본질.
- **CAS 의 win: contender 응답 분포가 균일해짐**. #9 에서 p50/p99/max 가 235ms 한 점에 cluster — 비관적 락은 winner COMMIT 시 wake-up jitter 로 235~411ms 분산.

## 2026-05-18 비관적 락 시점 측정

raw output 은 각 `concurrency/scenario-{7,9,10,12}-output.txt` 의 상단 블록 참조.
