package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
