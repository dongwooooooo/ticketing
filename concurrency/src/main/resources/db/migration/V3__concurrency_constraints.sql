-- Stage 2: 동시성 정합성 보장 제약
-- basic에서 의도 누락했던 두 제약을 여기서 추가한다.

-- (1) 결제 멱등성: 같은 idempotency_key 동시 INSERT 시 99건은 23505로 차단
ALTER TABLE payment_attempt ADD CONSTRAINT uq_payment_attempt_key UNIQUE (idempotency_key);

-- (2) 좌석당 활성 예약 1건 보장 (HELD 또는 PAID 상태의 reservation이 좌석마다 1건만)
--    같은 좌석에 동시 reservation INSERT 100건이 들어와도 99건은 unique violation
CREATE UNIQUE INDEX uq_reservation_seat_active
    ON reservation (seat_id)
    WHERE status IN ('HELD', 'PAID');
