# 구현 결정 기록 (Stage 1)

각 구현 결정의 **왜**와 **무엇을 의도적으로 안 했는가**.

## 0. Stage 1의 메타 결정

본 레포의 목적은 **race condition을 의도적으로 가지는 baseline**이다. Stage 2 측정의 비교 대상.

- 코드가 잘못된 게 아니라 **의도적으로 단순**하다
- 모든 의도적 결함은 `docs/known-issues.md`와 `docs/flow.md` §6에 명시
- "왜 락 안 썼나" 질문에 "Stage 1은 baseline, Stage 2에서 락 도입 측정" 답변

## 1. 도메인 모델

### 1.1 좌석을 row 단위로 분할 (50,000 row)

채택: VIP 2K + R 8K + S 15K + A 15K + 스탠딩 10K = 50,000 row

대안:
- (A) 단일 `inventory(section_id, total, remain)` 테이블 — 좌석 번호 미지원
- (B) 좌석마다 row — **채택**

근거:
- 좌석 단위 락이 자연 sharding 효과 (인기 좌석만 hot spot)
- 좌석 번호 표시 필요 (BTS 콘서트 도메인은 번호 지정)
- 50,000 row는 PG 16에 부담 없음

### 1.2 스탠딩 10,000을 좌석으로 흡수

채택: 좌석 번호 1~10,000으로 row 생성.

대안:
- (A) 별도 `Inventory(section_id, remain)` 카운터 — 도메인 분기 발생
- (B) 좌석화 — **채택, 메시지 단순화**

근거: 락 패턴 통일. Stage 2에서 좌석 단위 락 1안으로 모든 케이스 커버.

### 1.3 PaymentAttempt 분리

채택: `Payment` 별도, `PaymentAttempt(idempotency_key, request_hash)` 별도.

근거:
- 같은 결제(Payment)에 여러 시도(Attempt)가 있을 수 있음 (재시도)
- idempotency_key는 시도 단위. payment_id는 nullable (PG 호출 전에도 attempt 기록)
- Stage 2에서 attempt에 UNIQUE constraint만 추가하면 됨

### 1.4 환불 (Refund) 미포함

채택: Stage 1 범위 밖. `refund_request` 테이블도 V1 DDL에 없음.

근거: 5/24 마감 + 핵심 메시지(동시성) 분리. 환불은 Stage 3+.

## 2. Spring 설정

### 2.1 Spring Boot 4.0.0 + Java 25

채택: 최신 stable.

근거:
- 면접관에게 "현재 stable" 신호
- Boot 4 신규 API (split webmvc, spring-boot-testcontainers) 학습 기회

리스크: Boot 4 RestTemplateBuilder 패키지 이동 → 코드에서 직접 `new RestTemplate()` 사용

### 2.2 HikariCP pool size 10

채택: 기본 10.

근거:
- Stage 2 §1.1 cascade 재현 테스트에서 의도적으로 작은 pool로 고갈 시뮬레이션 가능
- 운영 환경 가정 시 더 큰 pool (`(core_count × 2) + effective_spindle_count` HikariCP 공식)

### 2.3 JPA `ddl-auto: validate`

채택: Flyway로 스키마 관리, JPA는 검증만.

근거:
- 운영 환경 표준 (`update`나 `create`는 운영에 위험)
- 의도적 누락(idempotency_key UNIQUE 없음 등)을 JPA가 자동 추가하지 못하게 함

### 2.4 PG mock (실 PG 통합 X)

채택: `MockPaymentGateway` 내부 비동기 callback 발사.

근거:
- 토스/포트원 통합은 실제 PG 계정 필요
- Stage 1~4 모두 동일 mock 사용 → 비교 일관성
- 환경변수로 success-rate / duplicate-callbacks 제어 → 멱등성/만료-결제 race 시나리오 직접 트리거

## 3. 인증

채택: `AuthContext` mock — `X-User-Id` 헤더 그대로 추출. JWT 검증 없음.

근거:
- 동시성 메시지 흐리지 않음
- 실제 IdP 통합은 Stage 4 또는 별도 학습 주제

## 4. naive 구현 의도

### 4.1 `ReservationService.reserve()` — 락 없음

```java
Seat seat = seatRepository.findById(seatId).orElseThrow();
if (seat.getStatus() != AVAILABLE) throw;
seat.hold();
seatRepository.save(seat);
```

의도: Read-Modify-Write race를 가장 단순한 형태로 노출.

Stage 2 변형:
- B안: `@Lock(PESSIMISTIC_WRITE)` 추가 (Repository 메서드 한 줄)
- C안: `Reservation(seat_id) UNIQUE` partial index + INSERT ON CONFLICT
- D안: `@Version` + Optimistic + retry

### 4.2 `PaymentService.request()` — find-then-insert race

```java
var existing = paymentAttemptRepository.findFirstByIdempotencyKey(key);
if (existing.isPresent()) return existing.payment;
INSERT payment + INSERT payment_attempt
```

의도: idempotency key 처리에서 race 노출.

Stage 2 해결: `payment_attempt.idempotency_key UNIQUE` + `try { INSERT } catch (DataIntegrityViolationException) { findExisting }`.

### 4.3 `PaymentService.handleCallback()` — 무조건 상태 전이

```java
payment.confirm();         // 무조건
reservation.markPaid();    // 무조건
seat.confirm();            // 무조건
```

의도: callback 중복 시 중복 상태 변경. 만료 처리와 동시 진입 시 lost update.

Stage 2 해결: `UPDATE WHERE status='REQUESTED'`로 atomic 전이, affected rows == 0이면 무시.

### 4.4 `ExpiryService.expireOverdueReservations()` — 단순 loop

```java
List<Reservation> overdue = repo.findByStatusAndExpiresAtBefore(HELD, now());
for (r : overdue) {
    r.markExpired();
    seatRepository.save(...);
}
```

의도: 만료 처리와 결제 callback의 lost update race.

Stage 2 해결: `UPDATE reservation SET status='EXPIRED' WHERE id=? AND status='HELD' RETURNING seat_id` atomic UPDATE.
Stage 4 해결: 다중 인스턴스 중복 실행 ShedLock.

## 5. 테스트 결정

### 5.1 Testcontainers PostgreSQL

채택: `spring-boot-testcontainers` + `@ServiceConnection`.

근거:
- H2 vs PG 락 동작 차이로 테스트는 통과하지만 운영 깨지는 함정 회피
- 모든 테스트가 같은 PG 버전 (16) 사용

리스크: Docker daemon 필요. 로컬 환경에 따라 DOCKER_HOST 설정 필요 (`build.gradle` test 태스크에 환경변수 분기 포함).

### 5.2 race 재현 테스트 결정적이지 않음

채택: `SeatRaceReproTest`는 race "가능성" 입증 목적.

근거:
- JPA flush 타이밍 + DB 격리수준 + 단일 머신 CPU 스케줄링에 의존
- "race가 항상 발생"이 아니라 "race가 발생할 수 있다"가 baseline 정확한 표현
- 결정적 검증은 Stage 2 수정 전후 비교에서 한다

## 6. 외부 의존성

- PostgreSQL 16 (Docker)
- Lombok (보일러플레이트 제거, 운영 영향 없음)
- Spring Validation (`@Valid`로 입력 검증)
- Spring Actuator (`/actuator/health` 등)

의도적 제외: Redis, Kafka, ShedLock, Resilience4j — 모두 Stage 2+ 도입.
