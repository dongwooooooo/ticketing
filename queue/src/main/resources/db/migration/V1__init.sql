-- 도메인 초기 스키마.
-- Stage 1과 동일 구조. Stage 2의 동시성 제약은 V3에서 추가.

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
CREATE INDEX idx_reservation_expires_held ON reservation(expires_at) WHERE status = 'HELD';
CREATE INDEX idx_reservation_seat ON reservation(seat_id);
-- 좌석당 활성 reservation 1건 보장 partial UNIQUE는 V3에서 추가

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
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_attempt_key ON payment_attempt(idempotency_key);
-- idempotency_key UNIQUE 제약은 V3에서 추가
