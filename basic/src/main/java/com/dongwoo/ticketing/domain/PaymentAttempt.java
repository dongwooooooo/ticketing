package com.dongwoo.ticketing.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "idempotency_key", nullable = false, length = 300)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentAttemptStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private PaymentAttempt(Long paymentId, String idempotencyKey) {
        this.paymentId = paymentId;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentAttemptStatus.REQUESTED;
    }

    public static PaymentAttempt of(Long paymentId, String idempotencyKey) {
        return PaymentAttempt.builder()
                .paymentId(paymentId)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
