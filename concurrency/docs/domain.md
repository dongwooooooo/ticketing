# Domain — ERD + 핵심 플로우

## ERD

```mermaid
erDiagram
    EVENT ||--o{ SCHEDULE : has
    SCHEDULE ||--o{ SECTION : has
    SECTION ||--o{ SEAT : contains
    SEAT ||--o{ RESERVATION : "1 active HELD/PAID"
    RESERVATION ||--o| PAYMENT : "0..1"
    PAYMENT ||--o{ PAYMENT_ATTEMPT : "idempotency keys"

    EVENT {
        bigint id PK
        varchar name
        varchar organizer
    }
    SCHEDULE {
        bigint id PK
        bigint event_id FK
        timestamp starts_at
        timestamp sales_open_at
    }
    SECTION {
        bigint id PK
        bigint schedule_id FK
        varchar name "VIP/R/S/A/STANDING"
        int price
    }
    SEAT {
        bigint id PK
        bigint section_id FK
        int seat_no
        varchar status "AVAILABLE/HELD/SOLD"
    }
    RESERVATION {
        bigint id PK
        bigint seat_id FK
        varchar user_id
        varchar status "HELD/PAID/EXPIRED/CANCELLED"
        timestamp expires_at
    }
    PAYMENT {
        bigint id PK
        bigint reservation_id FK
        int amount
        varchar status "REQUESTED/CONFIRMED/FAILED"
    }
    PAYMENT_ATTEMPT {
        bigint id PK
        bigint payment_id FK
        varchar idempotency_key UK
        varchar status
    }
```

좌석당 활성 reservation 1건은 partial UNIQUE index (`WHERE status IN ('HELD','PAID')`)로 보장.
idempotency_key는 UNIQUE constraint로 멱등 race 차단.

## 핵심 플로우 — 예매 → 결제 → callback

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant API as TicketingAPI
    participant DB as PostgreSQL
    participant PG as MockPaymentGateway

    U->>API: POST /seats/{seatId}/reservations
    API->>DB: SELECT seat FOR UPDATE
    Note over API,DB: row lock 획득
    API->>DB: UPDATE seat status=HELD
    API->>DB: INSERT reservation (partial UNIQUE 통과)
    API-->>U: 201 reservationId

    U->>API: POST /payments (Idempotency-Key)
    API->>DB: INSERT payment_attempt (UNIQUE race 차단)
    alt 멱등 hit
        DB-->>API: 23505 (UNIQUE violation)
        API->>DB: SELECT existing attempt
        API-->>U: 200 existing payment (replay)
    else 신규
        DB-->>API: OK
        API->>PG: dispatchPaymentCallback (async)
        API-->>U: 202 paymentId
    end

    PG->>API: POST /payments/callback
    API->>DB: UPDATE reservation SET status=PAID WHERE status=HELD
    Note over API,DB: affected rows = 1 이면 결제 확정
    API->>DB: UPDATE seat status=SOLD
    API-->>PG: 200
```

## Reservation 상태 전이

```mermaid
stateDiagram-v2
    [*] --> HELD: reserve()
    HELD --> PAID: callback SUCCESS<br/>(atomic UPDATE WHERE status=HELD)
    HELD --> EXPIRED: scheduler<br/>(expires_at < now)
    HELD --> CANCELLED: user cancel
    PAID --> [*]
    EXPIRED --> [*]: seat release
    CANCELLED --> [*]: seat release
```

만료-callback race 시 둘 중 1건만 `affected rows == 1`. 다른 트랜잭션은 no-op (callback 측 affected=0이면 환불 큐).

## 4-stage 진화

```mermaid
graph LR
    B[basic<br/>단일 서버<br/>race 의도적 잔존]
    C[concurrency<br/>락+UNIQUE+atomic UPDATE<br/>race 차단]
    Q[queue<br/>대기열로 backend 보호<br/>200 sustained / 5K peak]
    D[distributed<br/>다중 인스턴스<br/>ShedLock + fencing]

    B -->|I-001/2/3 해결| C
    C -->|I-004 해결| Q
    Q -->|I-005/6 해결| D
```

각 stage 책임 매트릭스:

| 이슈 | basic | concurrency | queue | distributed |
|---|---|---|---|---|
| I-001 좌석 oversell | (방치) | ✅ Pessimistic+UNIQUE | – | – |
| I-002 멱등성 | (방치) | ✅ UNIQUE+catch | – | – |
| I-003 만료-callback race | (방치) | ✅ atomic UPDATE | – | – |
| I-004 트래픽 backend 직격 | (방치) | (방치) | ✅ 대기열 | – |
| I-005 스케줄러 다중 실행 | (방치) | (방치) | (방치) | ✅ ShedLock |
| I-006 분산 좌석 락 | (방치) | (방치) | (방치) | ✅ Redis + fencing |
