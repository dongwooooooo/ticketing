# DD-3 만료-callback race — Atomic UPDATE WHERE status=expected

5-block 의사결정 일지: 직관 → 비판 → 대안 → 기각 → 채택+측정 → 한계

## 1. 직관

"만료 스케줄러랑 PG callback이 동시에 같은 row 건드리면 lost update니까 락으로 묶자."

## 2. 비판

만료 스케줄러는 **N개 reservation을 batch 처리**, callback은 **단일 reservation**을 다룬다.

- 둘을 Pessimistic Lock으로 묶으면 만료 처리 중 다른 reservation도 callback이 막힘 → throughput 폭락
- 만료 처리 트랜잭션이 길어지면 cascade
- 락은 정합성 도구이지 throughput 도구가 아님

DB는 row-level lock + WHERE 조건 평가를 atomic UPDATE 한 발로 제공한다. 락 명시 없이 race 차단 가능.

## 3. 대안

| # | 패턴 | 평가 |
|---|---|---|
| A | Atomic `UPDATE WHERE status=expected` (각 트랜잭션 단일 SQL) | affected rows 0/1로 race 결정. 락 명시 X |
| B | Pessimistic Lock + status check | 정합성 OK. 처리량 ↓ |
| C | OCC `@Version` | retry loop. 만료 batch 안에서 retry storm 가능 |
| D | SELECT FOR UPDATE 후 UPDATE | A보다 RTT 1회 추가 |
| E | Stored procedure로 묶기 | DB 종속 ↑. 디버깅 어려움 |
| F | 만료 처리를 별도 큐로 비동기화 | over-engineering. Stage 3+ |

## 4. 기각

- **B Pessimistic**: 만료 batch가 N개 row를 잡으면 callback이 전부 대기. ❌
- **C OCC**: 만료 batch가 100건 처리 시도 시 동시 callback과 충돌로 retry storm. ❌
- **D SELECT FOR UPDATE + UPDATE**: A와 정합성 같음. RTT 추가. ❌
- **E Stored proc**: 본 lab 범위 외. ❌
- **F 비동기 큐**: Stage 3 주제. ❌

## 5. 채택 + 측정

**채택: A — atomic UPDATE WHERE status=expected**

```java
// callback path
int affected = reservationRepository.updateStatusIfCurrent(
        reservationId, ReservationStatus.HELD, ReservationStatus.PAID);
if (affected == 0) {
    // 이미 만료/취소된 reservation — 환불 큐
    log.warn("Payment success but reservation no longer HELD — refund needed.");
    payment.confirm();
    return;
}
```

```java
// expiry batch
int affected = reservationRepository.expireOverdue();  // UPDATE WHERE status='HELD' AND expires_at < now()
```

이유:
1. 두 트랜잭션이 같은 row를 노려도 row-level lock + WHERE 조건 평가로 1건만 affected=1
2. 락 명시 없음 → 외부 호출과 무관 (callback path는 DB UPDATE 1번 + PG는 이미 끝남)
3. affected=0인 callback은 자동으로 "환불 필요" 분기 → 운영상 안전

상태 전이도:

```
        ┌─ callback(SUCCESS) 먼저 도착 ──→ PAID (만료 처리는 affected=0, no-op)
HELD ──┤
        └─ 만료 스케줄러 먼저 도달 ──→ EXPIRED (callback은 affected=0, 환불 큐)
```

측정 시나리오 + 합격선: [../deep-dives.md](../deep-dives.md) §DD-3.

### 측정 결과 (실측 후 기록)

| 항목 | 측정값 |
|---|---|
| 100회 반복 시 PAID:EXPIRED 분포 | _측정 후_ |
| `affected=0`인 callback 비율 (환불 큐 도달) | _측정 후_ |
| 두 트랜잭션 모두 affected=1인 case | 0 (가설 검증) |
| seat 좌석 정합성 위반 case | 0 (가설 검증) |

## 6. 한계

1. **환불 큐 미구현**: 본 lab은 `log.warn`만. 운영에선 outbox 또는 별도 메시지 큐 필요. 로그가 누락되면 ghost 환불 발생.
2. **payment.confirm() 강제 실행**: callback이 SUCCESS이고 reservation이 이미 EXPIRED여도 payment는 confirm으로 처리. PG는 이미 차감했으므로 payment row 자체는 정확. 단, 환불 처리 안 되면 회계 불일치.
3. **seat release loop의 windowing**: 만료된 seat을 `findSeatIdsRecentlyExpired(since)`로 조회 → 락 잡고 release. 이 사이에 또 다른 expiry batch가 돌면 같은 seat을 둘이 처리할 수 있음. 본 stage는 단일 인스턴스 가정으로 무시. Stage 4에서 ShedLock.
4. **expires_at의 시계 의존**: 분산 환경에선 인스턴스마다 시계가 다를 수 있음 (clock skew). 본 stage는 DB의 `now()`만 신뢰 → 단일 인스턴스 시 OK.
5. **UPDATE 후 좌석 release 분리**: atomic UPDATE가 reservation만 처리. seat은 별도 트랜잭션에서 lock 후 release. 두 단계 사이에 장애 발생 시 reservation=EXPIRED, seat=BOOKED인 inconsistent state 발생 가능. 운영에선 outbox + retry로 복구.
