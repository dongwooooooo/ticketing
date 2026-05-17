# Stage 3 (대기열) 진입 논리 — 실측 근거

## 한 줄 요약

Stage 2 베이스라인 (비관적 락 + partial UNIQUE) 은 race 정합성은 보장하지만, 50K 좌석 × 20만 동시 사용자 환경에서 풀 고갈·Deadlock·Lock timeout·Starvation·Connection leak 5종이 운영 부하에서 재현됨 → 대기열 필요.

## 실측 데이터 (별도 레포 `seat-lock-alternatives` 에서 측정)

### 1. 채택 베이스라인 자체 한계 — `stress-baseline/`

| 시나리오 | total | success | p99 (ms) | throughput (ops/s) |
|---|---|---|---|---|
| Hot Seat 1000 동시 | 1000 | 1 | 586 | 1490 |
| Distributed 1000×2000 동시 | 2000 | 808 | 1240 | 1385 |
| Pool Exhaustion 500 동시 (pool=10) | 500 | 99 | 123 | 3268 |

- race 차단 OK (좌석당 heldCount=1 유지).
- 그러나 **hot seat p99 = 586ms, distributed p99 = 1240ms** — 사용자 1초+ 대기.
- 위 시나리오에선 connectionTimeout=0. 단, 좌석 hold 트랜잭션이 짧기 때문이고 외부 결제 호출이 들어가면 즉시 무너짐 (시나리오 4 참조).

### 2. 추가 실패 모드 6종 — `stress-baseline-deep/`

| # | 실패 모드 | 결과 | 운영 영향 |
|---|---|---|---|
| 1 | JPA persistence-context staleness | PASS (race 차단됨) | `@Lock + @Query` 패턴 안전. ad-hoc `em.find()` 후 `@Lock` 호출은 금지 |
| 2 | **Deadlock** (다중 row + 잘못된 ordering) | **59/60 deadlock**, p99=12.5s | 좌석 묶음 예약·이동·교환 기능 추가 시 즉시 발생 |
| 3 | **Lock wait timeout** | **56/100 거절 @ 2초** | `SET LOCAL lock_timeout` 명시 필수. 미설정 시 사용자 대기 무한 |
| 4 | **Long-running tx starvation** | p99=2068ms (1 thread만 2s sleep) | 외부 API in tx → 좌석당 0.5 ops/sec — hot seat 즉시 마비 |
| 5 | Rollback storm | atomic 유지 (orphan=0) | 단일 노드 OK. 분산 시 outbox 패턴 필요 |
| 6 | **Connection leak** | leak 30 (=pool size) = probe timeout 5/5 | 단일 close 누락으로 인스턴스 영구 다운 |

## 진입 결정

대기열 도입은 위 5종 실패 모드를 **backend에 도달하기 전에 차단**하기 위함.

### 대기열이 차단하는 것

| 실패 모드 | 대기열 도입 시 |
|---|---|
| Hot seat 락 대기 | 대기열이 단일 좌석 동시 진입을 N건/초로 제한 → 락 큐가 쌓이지 않음 |
| Pool 고갈 | 대기열이 backend 직격 트래픽을 흡수 → 풀 점유 누적 안 됨 |
| Deadlock | 묶음 예약 시 대기열 안에서 ordering 강제 가능 |
| Long tx starvation | critical section 짧게 유지 (외부 호출은 큐 외부로 분리) |
| Lock timeout | 대기열 차단으로 backend 도달 자체가 줄어듦 — lock_timeout은 안전 그물 |

### 대기열이 못 차단하는 것 (Stage 4 영역)

- 멀티 인스턴스 시 분산 락 (단일 노드 대기열은 한 인스턴스의 queue에 묶임)
- 외부 결제 API 의존 (response 미수신 시 idempotency + outbox 필요)
- Connection leak (이는 코드 review + `try-with-resources` 강제로 해결)

## 측정 환경

- PostgreSQL 16, Docker Engine 24.0.5, API 1.43 pin
- HikariCP pool 다양 (10, 30)
- Java 25 Corretto, Spring Boot 4.0.0
- Testcontainers 2.0.2 + ServiceConnection

## 다음 단계

1. **Stage 3 구현 (지금)**: 백엔드 앞단에 대기열 (Redis Sorted Set 기반 또는 in-process queue). 최대 동시 진입 N건/초 제한. 토큰 발행 + 만료.
2. **Stage 4 구현 (이후)**: 멀티 인스턴스, 분산 락 (Redis Redlock 또는 PG advisory lock), outbox + reconciliation job, 결제 API idempotency.

## 참고

- 측정 코드: https://github.com/dongwooooooo/seat-lock-alternatives
- 메인 포트폴리오 §10: https://github.com/dongwooooooo/ticketing/blob/main/docs/measurements.md
