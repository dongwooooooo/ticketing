package com.dongwoo.ticketing.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Stage 4 — Outbox pattern.
 *
 * 결제 callback handler 는 짧은 tx 안에 outbox INSERT 만 하고 200 OK 반환.
 * 무거운 작업 (좌석 confirm, payment.status='APPROVED', notification 등) 은 worker 가 폴링하여 비동기 처리.
 *
 * status 전이:
 *  - PENDING → PROCESSING → DONE (성공)
 *  - PENDING → PROCESSING → PENDING (실패, attempts++, last_error 기록)
 *  - max attempts 초과 → DEAD (수동 개입)
 *
 * 멱등 보장:
 *  - aggregate_id (예: payment_id) 로 dedup 가능. worker 처리 로직이 idempotent 해야 함.
 */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Builder
    private OutboxEvent(String eventType, String aggregateId, String payload) {
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = "PENDING";
        this.attempts = 0;
    }

    public static OutboxEvent create(String eventType, String aggregateId, String payload) {
        return OutboxEvent.builder()
                .eventType(eventType)
                .aggregateId(aggregateId)
                .payload(payload)
                .build();
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = "PENDING";
    }

    public void markDone() {
        this.status = "DONE";
        this.processedAt = LocalDateTime.now();
    }

    public void markFailed(String err) {
        this.attempts++;
        this.lastError = err;
        this.status = "PENDING";
    }

    public void markDead(String err) {
        this.status = "DEAD";
        this.lastError = err;
        this.processedAt = LocalDateTime.now();
    }
}
