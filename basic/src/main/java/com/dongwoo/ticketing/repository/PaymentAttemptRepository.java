package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    // Stage 1 의도적 naive — 동시 조회 시 race 발생 (Stage 2에서 UNIQUE constraint로 차단)
    Optional<PaymentAttempt> findFirstByIdempotencyKey(String idempotencyKey);
}
