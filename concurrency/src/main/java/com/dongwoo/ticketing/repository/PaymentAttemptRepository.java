package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    /**
     * Stage 2 Deep Dive 2 — 기존 attempt 조회 (멱등성 hit 응답용).
     * 신규 INSERT는 UNIQUE constraint(uq_payment_attempt_key)가 race 차단.
     * 동시 100건 INSERT 중 1건만 통과, 99건은 DataIntegrityViolationException.
     */
    Optional<PaymentAttempt> findByIdempotencyKey(String idempotencyKey);
}
