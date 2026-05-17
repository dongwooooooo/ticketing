# DD-2 결제 멱등성 — UNIQUE constraint + INSERT 실패 catch

5-block 의사결정 일지: 직관 → 비판 → 대안 → 기각 → 채택+측정 → 한계

## 1. 직관 (first thought)

"같은 `Idempotency-Key`로 두 번 결제 요청 오면 한 번만 처리해야 함. 락으로 묶자."

## 2. 비판

결제 API는 **외부 호출(PG callback 트리거) + 트랜잭션 길이 ↑** 조합이다.

락으로 묶을 때:
- 같은 reservation에 결제 시도하는 다른 요청들이 줄줄이 대기
- 트랜잭션 안에 PG 호출이 들어가면 timeout 시 락 보유가 길어짐 (anti-pattern §1.5)
- HikariCP pool 고갈 → 다른 좌석/사용자에도 cascade

락-free 패턴이 더 안전하다.

## 3. 대안

| # | 패턴 | 평가 |
|---|---|---|
| A | `Idempotency-Key` UNIQUE constraint + INSERT 실패 catch | DB가 race 거부. lock-free, 트랜잭션 짧음 |
| B | Pessimistic Lock on `Payment(reservation_id)` | 결제 시도 직렬화. cascade 위험 ↑ |
| C | Redis SETNX with TTL | 단일 노드 over-engineering. 분산 환경 시 fencing 필요 |
| D | App-level `ConcurrentHashMap<key, lock>` | 단일 인스턴스만 유효, 메모리 leak risk |
| E | OCC on `Payment.version` | 멱등성과 무관 (concurrency 다른 차원) |
| F | Outbox pattern + 별도 비동기 처리 | over-engineering. Stage 4 주제 |

## 4. 기각

- **B Pessimistic**: 락 보유 시간이 PG 호출만큼 길어짐. cascade 발생. ❌
- **C Redis**: 단일 노드 가정 위반. 본 Stage 범위 외. ❌
- **D ConcurrentHashMap**: 단일 인스턴스 한정 + 영구 보관 어려움. ❌
- **E OCC**: 멱등성 차원이 아님. ❌
- **F Outbox**: 본 Stage 범위 초과. ❌

## 5. 채택 + 측정

**채택: A — UNIQUE constraint + INSERT 실패 catch**

```sql
ALTER TABLE payment_attempt
    ADD CONSTRAINT uq_payment_attempt_key UNIQUE (idempotency_key);
```

```java
try {
    paymentAttemptRepository.saveAndFlush(
            PaymentAttempt.of(payment.getId(), idempotencyKey));
} catch (DataIntegrityViolationException e) {
    var existing = paymentAttemptRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(...);
    return paymentRepository.findById(existing.getPaymentId()).orElseThrow(...);
}
```

이유:
1. Lock-free → 트랜잭션 짧고 cascade 없음
2. DB 레벨에서 race 차단 → 단일 노드/분산 무관
3. 실패한 99건은 catch로 즉시 기존 결과 replay → 사용자에게 동일한 응답 (멱등성 정의 충족)

측정 시나리오 + 합격선: [../deep-dives.md](../deep-dives.md) §DD-2.

### 측정 결과 (실측 후 기록)

| 항목 | 측정값 |
|---|---|
| `paymentAttempt` row count (단일 key) | _측정 후_ |
| `Payment` row count (단일 reservation) | _측정 후_ |
| `success.get()` (멱등 hit 포함) | _측정 후_ |
| Avg latency for replay (99건) | _측정 후_ |
| Catch path 실행 횟수 | _측정 후_ |

## 6. 한계

1. **같은 key + 다른 amount**: 현재 코드는 amount를 무시하고 기존 응답 반환. 운영상 위험 (사용자가 5만원→25만원으로 다시 요청해도 5만원 결과 받음). 운영 환경에선 요청 본문 해시 검증을 attempt에 함께 저장하여 mismatch 시 409 conflict 반환 필요. 본 Lab 범위 초과.
2. **PG 호출 후 INSERT 실패 시점**: 현재 흐름은 `payment INSERT → attempt INSERT (race 차단)` 순서. attempt가 실패하면 payment row는 orphan. 운영에선 attempt INSERT를 먼저 하는 게 안전 (현재 reverse).
3. **DataIntegrityViolationException 의존**: Spring이 SQLState 23505를 통해 변환하지만, DB 종류/드라이버 변경 시 mapping이 깨질 수 있음. Postgres에 종속.
4. **TTL 없음**: idempotency key는 영구 보관. 1년 후 같은 key 재사용 시 stale 결과 반환. 운영에선 attempt에 TTL 필요.
