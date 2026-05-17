# 시나리오 카탈로그 (모든 스테이지 공통)

목적: 본 레포의 모든 스테이지(basic / concurrency / queue / distributed)에서 동일하게 다뤄야 할 **사용자·시스템·외부·장애 시나리오**를 한 곳에 정리. 각 스테이지는 자기 책임 영역 시나리오만 검증하고, 다른 영역은 다음 스테이지로 위임.

## 1. 분류 축

3단계 축으로 시나리오 식별. 분류 자체보다 "actor × 상태 전이"가 핵심.

| 축 | 값 |
|---|---|
| Actor | User / Scheduler / PaymentGateway / Admin / Server-instance |
| 상태 전이 대상 | Seat / Reservation / Payment |
| 다자성 | Single-actor (1명) / Multi-actor (N명) / System-event |

시나리오 ID 체계: `SCN-{영역}-{번호}`. 영역 = U(User) / M(Multi) / S(System) / P(Payment) / F(Failure).

## 2. 시나리오 카탈로그

각 시나리오:
- **트리거**: 발생 조건
- **기대 결과**: 정상 수렴 상태
- **위반 영향**: 정합성 깨졌을 때 사용자/비즈니스 피해
- **검증**: JUnit / k6 / 수동 curl
- **Stage 적용**: basic(B) / concurrency(C) / queue(Q) / distributed(D)에서 누가 책임

### 2.1 U — Single-User 시나리오

#### SCN-U-01 happy path
- 트리거: 조회 → 예매 → 결제 → callback SUCCESS
- 기대: Seat=SOLD, Reservation=PAID, Payment=CONFIRMED
- 위반 영향: 결제 차감됐는데 좌석 미배정 → CS 폭주
- 검증: `HappyPathIntegrationTest` (B✅), `scripts/smoke.sh`
- Stage 적용: B C Q D (모두)

#### SCN-U-02 예매 후 이탈 (결제 안 함)
- 트리거: HELD 후 5분 동안 결제 요청 안 보냄
- 기대: ExpiryScheduler가 EXPIRED 처리 + Seat AVAILABLE 복귀
- 위반 영향: 좌석 영구 점유 → 다른 사용자 예매 불가
- 검증: `ExpiryReproTest` (basic), `ExpiryAtomicTest` (concurrency)
- Stage 적용: B C Q D

#### SCN-U-03 명시 취소 (HELD 상태)
- 트리거: DELETE /reservations/{id} 호출
- 기대: Reservation=CANCELLED, Seat=AVAILABLE
- 위반 영향: 사용자가 취소했는데 좌석 안 풀림
- 검증: `CancelHeldTest` (B), `CancelCallbackRaceTest` (C)
- Stage 적용: B C Q D

#### SCN-U-04 결제 페이지 이탈 (PG callback 안 옴)
- 트리거: POST /payments 보내고 PG 결제창에서 닫음 → callback 없음
- 기대: Payment=REQUESTED 유지 + Reservation TTL 만료 시 EXPIRED + 환불 큐 enqueue
- 위반 영향: Payment 좀비 row, Reservation 만료됐는데 결제 진행 중 표시
- 검증: `PaymentAbandonedTest` (C)
- Stage 적용: C Q D

#### SCN-U-05 사용자 결제 재시도 (같은 idempotency-key)
- 트리거: 클라이언트 retry로 같은 key 2회 POST /payments
- 기대: 둘 다 200 응답 + 동일 PaymentId 반환 (멱등성)
- 위반 영향: 중복 결제 차감
- 검증: `IdempotentRetryTest` (C)
- Stage 적용: C Q D

#### SCN-U-06 사용자 결제 재시도 (다른 idempotency-key)
- 트리거: 같은 Reservation에 다른 key 2회 결제
- 기대: 2번째는 409 (reservation already paid 또는 already locked)
- 위반 영향: 중복 결제
- 검증: `DifferentKeySamePaymentTest` (C)
- Stage 적용: C Q D

### 2.2 M — Multi-User 시나리오

#### SCN-M-01 같은 좌석 동시 2명
- 트리거: User A, B가 동시에 같은 seatId 예매
- 기대: 1명 성공, 1명 409 실패
- 위반 영향: oversell — 1좌석 2명
- 검증: `SeatRaceReproTest` (B: 발생 검증), `SeatLockTest` (C: 차단 검증)
- Stage 적용: B(재현) C(차단) Q D

#### SCN-M-02 같은 좌석 동시 100명
- 트리거: 인기 좌석에 동시 100건
- 기대: 1명 성공, 99명 실패
- 위반 영향: oversell 다중 발생
- 검증: `SeatRaceReproTest` (B), `SeatLockConcurrencyTest` (C)
- Stage 적용: B C Q D

#### SCN-M-03 같은 구역 빈 좌석에 N명 (다른 좌석)
- 트리거: VIP 구역 2,000석에 동시 사용자 5,000명, 각자 다른 좌석 노림
- 기대: 2,000명 성공, 3,000명 빈 좌석 없음으로 실패
- 위반 영향: connection pool 고갈로 성공률 떨어짐
- 검증: k6 ramping-arrival-rate (Q)
- Stage 적용: Q D

#### SCN-M-04 BTS급 peak — 동시 접속 50만, peak 5K TPS
- 트리거: 오픈 0~10초 사이 5K TPS 결제 요청
- 기대: 200 TPS sustained 처리 + 대기열로 5K → 200 throttle
- 위반 영향: 서버 crash, DB pool 고갈, oversell
- 검증: k6 (Q,D), 가상 대기열 동작 확인
- Stage 적용: Q D

#### SCN-M-05 인기 좌석 vs 일반 좌석 트래픽 편향 (Pareto)
- 트리거: VIP 2,000석에 80% 트래픽 집중, 나머지 좌석에 20%
- 기대: 좌석 단위 락은 hot 좌석에만 직렬화 영향 + 다른 좌석은 자유
- 위반 영향: 좌석 단위 락 vs 이벤트 단위 락 trade-off 측정
- 검증: k6 skewed workload (Q,D)
- Stage 적용: Q D

#### SCN-M-06 동일 사용자가 N좌석 hold 시도
- 트리거: User A가 좌석 1~10을 연속 HELD
- 기대: **검토 필요** — Stage 별로 제한 정책 (1인 최대 4좌석 등) 적용 여부
- 위반 영향: 사재기, 다른 사용자 기회 박탈
- 검증: `MultiHoldLimitTest` (정책 도입 시)
- Stage 적용: 정책 미도입 시 모두 ✅, 도입 시 C부터

### 2.3 P — Payment 외부 시나리오

#### SCN-P-01 callback SUCCESS
- SCN-U-01의 일부. happy path

#### SCN-P-02 callback FAIL (한도 초과, 카드 분실, PG 거절)
- 트리거: PG가 FAIL 결과로 callback
- 기대: Payment=FAILED, Reservation=CANCELLED, Seat=AVAILABLE
- 위반 영향: 좌석 안 풀림 → 다른 사용자 불가
- 검증: `PaymentFailTest` (B), `PaymentFailCascadeTest` (C)
- Stage 적용: B C Q D

#### SCN-P-03 callback 중복 도착 (PG retry)
- 트리거: PG가 같은 paymentId로 callback 10회 (포트원 timeout 정책)
- 기대: 1번만 상태 변경, 나머지 9번은 idempotent (no-op)
- 위반 영향: 중복 처리, audit log 오염
- 검증: `CallbackDuplicateTest` (C)
- Stage 적용: C Q D

#### SCN-P-04 callback timeout (30초 응답 없음)
- 트리거: 우리 서버가 callback 처리 도중 30초 초과
- 기대: PG가 재시도 발사 → SCN-P-03으로 수렴
- 위반 영향: PG가 우리 응답 못 받음 → 무한 retry
- 검증: `CallbackTimeoutTest` (C)
- Stage 적용: C Q D

#### SCN-P-05 callback 영원히 안 옴 (PG 다운)
- 트리거: PG가 응답 자체 못 보냄
- 기대: Payment=REQUESTED 무한 유지 + Reservation TTL 만료 + 환불 큐 enqueue (PG 복구 시 자동 환불)
- 위반 영향: 좌석 영구 점유 또는 결제 진행 중 표시
- 검증: 운영 monitoring + manual cron 환불 점검
- Stage 적용: D (Outbox 패턴 도입 시)

#### SCN-P-06 callback 순서 뒤바뀜
- 트리거: PG가 비동기라 paymentA(이른 결제) callback이 paymentB(늦은 결제)보다 늦게 도착
- 기대: paymentId 단위로 처리 → 순서 무관 결과 동일
- 위반 영향: 순서 의존 코드 있으면 lost update
- 검증: `CallbackOutOfOrderTest` (C)
- Stage 적용: C Q D

### 2.4 S — System 이벤트 시나리오

#### SCN-S-01 만료 스케줄러 발사
- 트리거: `@Scheduled(fixedDelay=5000)` 5초마다
- 기대: HELD + expires_at < now() 인 reservation을 EXPIRED 처리
- 위반 영향: 만료 처리 누락 → 좌석 영구 점유
- 검증: `ExpiryServiceTest` (B,C)
- Stage 적용: B C Q D

#### SCN-S-02 만료 처리 vs 결제 callback 동시 진입
- 트리거: 만료 timer 발사 + PG callback이 같은 reservation에 동시
- 기대: 둘 중 하나만 affected rows == 1, 다른 하나는 == 0 + 후속 처리 무시
- 위반 영향: PAID 상태가 EXPIRED로 덮임 → 사용자 결제 차감됐는데 좌석 없음
- 검증: `ExpiryPaymentRaceTest` (C, fuzzing 100회)
- Stage 적용: C Q D

#### SCN-S-03 사용자 취소 vs callback 동시
- 트리거: User가 DELETE 호출 + PG callback이 동시
- 기대: 둘 중 하나만 commit (취소가 이기면 환불 큐, callback이 이기면 PAID 유지)
- 위반 영향: 취소했는데 결제 됨 또는 결제 됐는데 취소 됨
- 검증: `CancelCallbackRaceTest` (C)
- Stage 적용: C Q D

#### SCN-S-04 만료 스케줄러 다중 인스턴스 중복 발사
- 트리거: Spring 인스턴스 N개에서 같은 시각 `@Scheduled` 발사
- 기대: ShedLock으로 한 인스턴스만 실행
- 위반 영향: 같은 reservation을 두 번 EXPIRED 처리 시도 → 두 번째는 affected rows == 0이라 무시되나 audit log 오염 + DB 부하
- 검증: `MultiInstanceShedLockTest` (D)
- Stage 적용: D

### 2.5 F — Failure 시나리오 (Stage 4 영역)

#### SCN-F-01 트랜잭션 중 Spring 인스턴스 crash
- 트리거: SeatService 호출 중 JVM 죽음
- 기대: 트랜잭션 rollback → DB는 원상태. HELD 안 됨
- 위반 영향: row lock 잡은 채 죽으면 DB가 idle-in-transaction 감지 후 abort
- 검증: 통합 테스트로 검증 어려움 → 운영 monitoring (long-running tx)
- Stage 적용: D

#### SCN-F-02 PG 일시적 timeout 시 우리 timeout
- 트리거: PG 호출이 25초 응답 (timeout 30s 안)
- 기대: 우리 서비스 timeout 짧게 (예: 5초) + retry with backoff
- 위반 영향: HikariCP pool 고갈, cascade
- 검증: WireMock으로 지연 주입 (D)
- Stage 적용: D

#### SCN-F-03 DB connection pool 고갈
- 트리거: 락 보유 시간 길어지면서 HikariCP 10/10 사용
- 기대: 11번째 요청 connection-timeout 3초 안 503 응답
- 위반 영향: 사용자 fail 폭증
- 검증: `LockCascadeReproTest` (C)
- Stage 적용: C D

#### SCN-F-04 Redis 다운 (Stage 2+ Redis 도입 시)
- 트리거: Redis 컨테이너 kill 후 결제/좌석 요청
- 기대: DB fallback으로 graceful degrade
- 위반 영향: 결제/예매 전체 불가
- 검증: `RedisFailoverTest` (D)
- Stage 적용: D

#### SCN-F-05 네트워크 분할 (split-brain)
- 트리거: 다중 인스턴스 사이 네트워크 끊김
- 기대: fencing token으로 zombie lock 차단 (Kleppmann)
- 위반 영향: 같은 좌석을 두 노드에서 다른 사용자에게 판매
- 검증: 본 Lab 범위 밖. 합의 알고리즘 검증 비용 큼
- Stage 적용: D (가설만)

## 3. Stage별 책임 매트릭스

각 시나리오를 어느 스테이지가 책임지는지.

| 시나리오 | basic | concurrency | queue | distributed |
|---|---|---|---|---|
| SCN-U-01 happy path | ✅ | ✅ | ✅ | ✅ |
| SCN-U-02 이탈/만료 | 일부 | ✅ | ✅ | ✅ |
| SCN-U-03 자가 취소 | ✅ | ✅ | ✅ | ✅ |
| SCN-U-04 결제 페이지 이탈 | ❌ | ✅ | ✅ | ✅ |
| SCN-U-05 멱등 재시도 | ❌ race | ✅ | ✅ | ✅ |
| SCN-M-01 동시 2명 | ❌ race | ✅ | ✅ | ✅ |
| SCN-M-02 동시 100명 | ❌ race 재현 | ✅ | ✅ | ✅ |
| SCN-M-03 빈 좌석 N명 | - | 일부 | ✅ | ✅ |
| SCN-M-04 BTS peak | - | - | ✅ | ✅ |
| SCN-M-05 skewed workload | - | - | ✅ | ✅ |
| SCN-P-02 callback FAIL | ✅ | ✅ | ✅ | ✅ |
| SCN-P-03 callback 중복 | ❌ | ✅ | ✅ | ✅ |
| SCN-P-04 callback timeout | - | ✅ | ✅ | ✅ |
| SCN-P-05 callback 영원히 안 옴 | - | - | - | ✅ |
| SCN-P-06 callback 순서 뒤바뀜 | - | ✅ | ✅ | ✅ |
| SCN-S-01 만료 스케줄러 | ✅ | ✅ | ✅ | ✅ |
| SCN-S-02 만료-결제 race | ❌ race | ✅ | ✅ | ✅ |
| SCN-S-03 취소-callback race | ❌ race | ✅ | ✅ | ✅ |
| SCN-S-04 다중 인스턴스 스케줄러 | - | - | - | ✅ |
| SCN-F-01 인스턴스 crash | - | - | - | ✅ |
| SCN-F-02 PG timeout | - | 일부 | ✅ | ✅ |
| SCN-F-03 pool 고갈 | - | 재현 | ✅ | ✅ |
| SCN-F-04 Redis 다운 | - | - | - | ✅ |
| SCN-F-05 split-brain | - | - | - | 가설 |

범례:
- ✅ 명시적으로 차단/처리
- ❌ race 의도적 보존 (다음 스테이지에서 해결 근거)
- 일부 = 부분 처리
- "-" = 해당 스테이지 책임 아님

## 4. 시나리오 우선순위 매트릭스

(발생 빈도) × (위반 영향) = 우선순위.

| 시나리오 | 빈도 | 영향 | 우선순위 |
|---|---:|---:|---:|
| SCN-M-01/02 동시 좌석 | 매번 (인기 좌석) | oversell — catastrophic | **P0** |
| SCN-P-03 callback 중복 | 매번 (PG retry 사양) | 중복 결제 차감 | **P0** |
| SCN-S-02 만료-결제 race | 잦음 (TTL 임박) | PAID lost update | **P0** |
| SCN-U-05 멱등 재시도 | 잦음 (네트워크 불안정) | 중복 결제 | P1 |
| SCN-U-02 이탈/만료 | 매우 잦음 | 좌석 영구 점유 | P1 |
| SCN-S-03 취소-callback race | 가끔 | 결제 vs 취소 결정 모호 | P1 |
| SCN-P-02 callback FAIL | 가끔 | 좌석 안 풀림 | P1 |
| SCN-M-04 BTS peak | 드물게 (큰 이벤트) | 서버 crash | P1 |
| SCN-F-03 pool 고갈 | 락 안티패턴 시 | cascade | P2 |
| SCN-P-04 callback timeout | 드물게 | PG 재시도 폭증 | P2 |
| SCN-S-04 다중 인스턴스 스케줄러 | 다중 인스턴스 환경 | DB 부하 | P2 |
| SCN-F-01 crash | 드물게 | 운영 monitoring | P2 |
| SCN-P-05 PG 영원히 안 옴 | 매우 드물게 | 환불 큐로 회복 | P3 |
| SCN-F-05 split-brain | 매우 드물게 | 본 Lab 범위 밖 | P3 |

P0: 모든 스테이지에서 반드시 검증. 본 Lab core deliverable.
P1: Stage 2~3에서 처리. 면접 답변 핵심.
P2: Stage 3~4에서 처리. 운영 시나리오.
P3: 본 Lab 범위 밖 또는 가설만.

## 5. 시니어 비판 검토

### 5.1 분류 축이 충분한가

현재 axes: Actor × 상태 전이 × 다자성. 누락 가능성:

- **시간 축**: 같은 시나리오라도 sales_open_at 직전/직후/매진 임박 시 다른 패턴
- **데이터 양 축**: 좌석 1개 vs 2,000개(VIP) vs 50,000개(전체)
- **사용자 행동 패턴**: 첫 방문 vs 재방문, 결제 진행 중 다른 좌석 새 예매 시도
- **외부 시스템 다양성**: 토스 vs 포트원 vs 카카오페이 동시 사용 (다중 PG)

**판단**: 본 Lab 범위에선 현재 3축이 충분. 다중 PG/시간 축은 운영 단계 영역.

### 5.2 시나리오 단위가 너무 굵거나 너무 잘게 쪼개졌나

- 너무 굵음: SCN-M-04 "BTS peak"은 사실 SCN-M-01/02/03이 동시 발생하는 합성. 분리 필요?
  → **유지**. 합성 시나리오도 별도 검증 가치 있음 (개별 합 ≠ 시스템 한계)
- 너무 잘게: SCN-U-05 vs SCN-U-06은 같은 멱등성 시나리오 변형. 통합?
  → **유지**. 멱등성 동작 검증이 다름 (same-key vs different-key)

### 5.3 놓친 시나리오

**검토 추가 권고**:

- 좌석 hold 연장 (사용자가 결제 진행 중 추가 5분 요청) — UX 일반적이나 본 Lab Out-of-Scope
- 다중 좌석 한 번 예매 (4인 가족 4좌석 일괄) — 트랜잭션 범위 다름, 별도 시나리오 필요
- 좌석 hold 1인당 최대 N개 제한 — 사재기 방지, 정책 도입 시 SCN-M-06
- 결제 부분 환불 (4좌석 중 1좌석만) — Stage 3+ 영역
- 부정 결제 / 사기 탐지 — 본 Lab 영역 아님 (BotID, Vercel Firewall 같은 별도 서비스)
- 좌석맵 실시간 갱신 (다른 사용자 hold한 좌석 즉시 회색화) — SSE/WebSocket 영역, Stage 3 가상 대기열과 별개
- 좌석 가격 변동 (다이내믹 프라이싱) — Out
- 환불 후 좌석 재판매 — Stage 3+ 환불 도입 시
- 좌석 양도 (티켓 이전) — Out
- 부정 환불 (결제 후 즉시 환불 반복) — Out

**즉시 추가 권고**: 다중 좌석 일괄 예매 (`SCN-U-07`). 4인 가족 4좌석 트랜잭션이 1좌석과 락 패턴 달라짐.

### 5.4 분류 자체의 더 좋은 작성 방안

**옵션 A (현재)**: 영역(U/M/P/S/F) prefix + 번호
- 장점: 영역 단위 그룹화, 면접 답변 정렬 자연
- 단점: 합성 시나리오(SCN-M-04)가 어디 속하는지 모호

**옵션 B**: 상태 전이 단위 분류
- 모든 시나리오를 "어떤 상태 전이를 시도하나"로 분류 (HELD→PAID, HELD→EXPIRED, HELD→CANCELLED 등)
- 장점: 도메인 모델에 밀착, 누락 시나리오 발견 쉬움
- 단점: 다자 시나리오는 여러 전이 동시라 분류 모호

**옵션 C (추천)**: 옵션 A 유지 + 각 시나리오에 "관련 상태 전이" 메타 태그 추가
- 예: SCN-S-02에 "전이: Reservation.HELD→PAID + HELD→EXPIRED 경합" 태그

본 문서는 옵션 A. 옵션 C 메타 태그는 추후 추가 검토.

### 5.5 검증 단위 비판

각 시나리오를 JUnit / k6 / 수동 curl 중 무엇으로 검증할지:

- **JUnit**: 단일 트랜잭션 / 트랜잭션 경합 / race 재현 (소규모 동시성)
- **k6**: 대규모 부하 / TPS 측정 / p99 latency
- **수동 curl + DB 쿼리**: 시나리오 explore / 면접 데모용

**중복 비판**: race 시나리오를 JUnit과 k6 둘 다로 검증하면 중복 작업?
- **유지 권고**: JUnit은 정합성 (oversell 0건), k6는 처리량 (TPS / latency). 검증 목적이 다름

### 5.6 시나리오 → 테스트 매핑 누락

본 문서는 시나리오마다 "검증" 컬럼에 테스트 클래스명을 적었지만, 일부는 미작성 상태. Stage 2 진입 시 모든 P0/P1 시나리오에 대응하는 JUnit 클래스 생성 의무화 권고.

## 6. 적용 워크플로우

각 스테이지 시작 시:

1. 본 문서에서 해당 스테이지 책임 시나리오 목록 추출 (§3 매트릭스)
2. 우선순위 P0부터 JUnit 테스트 클래스 생성 (검증 컬럼 명시)
3. 시나리오마다 5블록 본인 사고 기록 (12번 문서 §5 템플릿)
4. 모든 P0+P1 통과 후 다음 스테이지로

## 7. 면접 답변 활용

시나리오 카탈로그가 면접 답변의 1차 근거.

면접관 "어떤 케이스를 다루셨어요?":
> SCN 카탈로그 30개 시나리오 중 본 스테이지에서 P0/P1 X개를 검증했습니다. 나머지는 다음 스테이지에서.

면접관 "이건 어떻게 되나요?" + edge case 제시:
> SCN-X-NN으로 카탈로그에 있고, 트리거는 ..., 기대 결과는 ..., 현재 ✅/❌ 상태입니다.

면접관 "왜 X 안 하셨어요?":
> §5.3 비판 검토에서 Out-of-Scope으로 분리. 근거 [이유].

## 8. 다음 단계

- [ ] 본 문서 P0/P1 시나리오 각각에 JUnit 테스트 클래스명 매핑 완료
- [ ] SCN-U-07 다중 좌석 일괄 예매 시나리오 추가 검토 결정
- [ ] 시니어 비판 §5.3 추가 시나리오 중 본 Lab에 포함할 것 확정
- [ ] 옵션 C 메타 태그 (상태 전이) 추가 여부 결정
- [ ] Stage별 진입 시 시나리오 매트릭스 자가 검증 의무화
