# 도메인 모델

## 엔티티

```
Event (콘서트, 1개)
  └─ Schedule (회차, 1개. starts_at = 2026-08-15 19:00 KST)
       └─ Section (구역, 5개: VIP/R/S/A/스탠딩)
            └─ Seat (좌석, 총 50,000)
                 └─ Reservation (예매, 0..N)
                      └─ Payment (결제, 1)
                           └─ PaymentAttempt (멱등성 슬롯)
```

## 좌석 구성 (50,000)

| 구역 | 좌석 수 | 가격 |
|---|---:|---:|
| VIP | 2,000 | 250,000원 |
| R | 8,000 | 180,000원 |
| S | 15,000 | 130,000원 |
| A | 15,000 | 90,000원 |
| 스탠딩 | 10,000 | 80,000원 |

근거: BTS 2026 ARIRANG 잠실주경기장 5만석급.

## 상태 전이

### Seat

```
AVAILABLE ──hold──> HELD ──confirm──> SOLD
              │
              └──cancel/expire──> AVAILABLE
```

### Reservation

```
HELD ──┬──payment confirmed──> PAID
       ├──timeout──> EXPIRED
       └──user cancel──> CANCELLED
```

### Payment

```
REQUESTED ──callback success──> CONFIRMED
        │
        └──callback fail──> FAILED
```

## API

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | /events/{eventId}/schedules/{scheduleId}/seats?status=AVAILABLE&section=VIP&page=0 | 좌석 목록 |
| POST | /events/{eventId}/seats/{seatId}/reservations | 좌석 선점 (HELD 5분 TTL) |
| DELETE | /reservations/{reservationId} | 자가 취소 (HELD 상태만) |
| POST | /payments | 결제 요청 (Idempotency-Key 헤더, 본 레포는 미검증) |
| POST | /payments/callback | PG 콜백 (본 레포는 멱등성 미적용) |
| GET | /reservations/{reservationId} | 상태 조회 |

## Flyway 마이그레이션

- V1__init.sql — 위 7개 테이블 생성
- V2__seed_event.sql — 이벤트 1 + 회차 1 + 5구역 + 좌석 50,000 seed

## 본 레포 가정

- 단일 서버 / 단일 인스턴스
- 사용자 인증은 JWT mock (헤더 user_id 추출만, 검증 X)
- PG는 mock callback (비동기 1초 후 callback 발사기)
- 동시성 제어 없음 (Known Issues 참고)
