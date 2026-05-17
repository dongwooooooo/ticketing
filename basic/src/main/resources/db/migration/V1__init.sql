-- Stage 1: ticketing-basic
-- 단일 서버 happy path 도메인. 동시성 제어는 의도적으로 없음.

CREATE TABLE event (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    organizer VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE schedule (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES event(id),
    starts_at TIMESTAMP NOT NULL,
    sales_open_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_schedule_event ON schedule(event_id);

CREATE TABLE section (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES schedule(id),
    name VARCHAR(50) NOT NULL,
    price INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (schedule_id, name)
);
CREATE INDEX idx_section_schedule ON section(schedule_id);

CREATE TABLE seat (
    id BIGSERIAL PRIMARY KEY,
    section_id BIGINT NOT NULL REFERENCES section(id),
    seat_no INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (section_id, seat_no)
);
CREATE INDEX idx_seat_section_status ON seat(section_id, status);

CREATE TABLE reservation (
    id BIGSERIAL PRIMARY KEY,
    seat_id BIGINT NOT NULL REFERENCES seat(id),
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservation_user_status ON reservation(user_id, status);
CREATE INDEX idx_reservation_expires_held ON reservation(expires_at) WHERE status = 'HELD';
CREATE INDEX idx_reservation_seat ON reservation(seat_id);
-- Stage 1 의도적 누락: seat_id에 활성 reservation 1건 보장하는 partial UNIQUE 없음
-- Stage 2에서 추가:
-- CREATE UNIQUE INDEX uq_reservation_seat_active ON reservation(seat_id) WHERE status IN ('HELD', 'PAID');

CREATE TABLE payment (
    id BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservation(id),
    amount INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    approved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_reservation ON payment(reservation_id);

CREATE TABLE payment_attempt (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT REFERENCES payment(id),
    idempotency_key VARCHAR(300) NOT NULL,
    request_hash VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
-- Stage 1 의도적 누락: idempotency_key UNIQUE 제약 없음 (중복 결제 재현용)
-- Stage 2에서 추가:
-- ALTER TABLE payment_attempt ADD CONSTRAINT uq_payment_attempt_idem UNIQUE (idempotency_key);
CREATE INDEX idx_payment_attempt_key ON payment_attempt(idempotency_key);
