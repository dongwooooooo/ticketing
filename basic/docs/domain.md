# Domain — Stage 1 (basic)

## ERD

```mermaid
erDiagram
    EVENT ||--o{ SCHEDULE : has
    SCHEDULE ||--o{ SECTION : has
    SECTION ||--o{ SEAT : contains
    SEAT ||--o{ RESERVATION : "race 가능 (Stage 1)"
    RESERVATION ||--o| PAYMENT : "0..1"
    PAYMENT ||--o{ PAYMENT_ATTEMPT : "naive idempotency"

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
        varchar status
    }
    PAYMENT_ATTEMPT {
        bigint id PK
        bigint payment_id FK
        varchar idempotency_key "UNIQUE 없음 (의도적)"
        varchar status
    }
```

Stage 1 의도적 누락:
- `seat_id`에 대한 partial UNIQUE 없음 → 같은 좌석 동시 INSERT 시 다중 HELD 가능 (I-001)
- `idempotency_key` UNIQUE 없음 → 같은 key 다중 INSERT 가능 (I-002)

## 플로우 — 예매 → 결제 → callback

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant API as TicketingAPI
    participant DB as PostgreSQL
    participant PG as MockPaymentGateway

    U->>API: POST /seats/{seatId}/reservations
    API->>DB: findById seat
    Note over API,DB: lock 없음 (Stage 1 naive)
    API->>DB: UPDATE seat HELD + INSERT reservation
    API-->>U: 201 reservationId

    U->>API: POST /payments (Idempotency-Key)
    API->>DB: findFirstByIdempotencyKey
    Note over API,DB: read-then-write race
    API->>DB: INSERT payment + INSERT attempt
    API->>PG: dispatchPaymentCallback (async)
    API-->>U: 202 paymentId

    PG->>API: POST /payments/callback
    API->>DB: UPDATE reservation PAID + UPDATE seat SOLD
    Note over API,DB: 만료 처리와 lost update 가능 (Stage 1 naive)
    API-->>PG: 200
```

## Reservation 상태 전이

```mermaid
stateDiagram-v2
    [*] --> HELD: reserve()
    HELD --> PAID: callback SUCCESS<br/>(lost update 가능)
    HELD --> EXPIRED: scheduler
    HELD --> CANCELLED: user cancel
    PAID --> [*]
    EXPIRED --> [*]
    CANCELLED --> [*]
```

Stage 1은 status 변경에 atomic UPDATE 없음. 동시 진입 시 비결정적 결과 (I-003).
