package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.domain.*;
import com.dongwoo.ticketing.infra.pgmock.MockPaymentGateway;
import com.dongwoo.ticketing.lock.DistributedSeatLock;
import com.dongwoo.ticketing.outbox.OutboxEvent;
import com.dongwoo.ticketing.outbox.OutboxRepository;
import com.dongwoo.ticketing.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 4 — Outbox 통합 결제.
 *
 * 변경 (Stage 3 → Stage 4):
 *  - handleCallback: 무거운 작업 (seat confirm, reservation update 등) 을 outbox INSERT 만 하고 200 응답.
 *  - 실제 처리는 OutboxWorker @Scheduled 가 폴링하여 별도 tx 로 처리.
 *  - 이렇게 하면 callback handler 의 응답 latency 가 짧고, 처리 도중 실패해도 worker 재시도 가능.
 *
 * 멱등성:
 *  - 동일 paymentId 의 callback 이 2번 와도 outbox INSERT 는 2건이지만, processCallback 의 reservation
 *    updateStatusIfCurrent 가 atomic 이라 affected=1 이 1번만 발생. 두 번째 row 는 no-op 처리 후 DONE.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final SeatRepository seatRepository;
    private final OutboxRepository outboxRepository;
    private final DistributedSeatLock seatLock;
    private final MockPaymentGateway gateway;

    @Transactional
    public Payment request(Long reservationId, Integer amount, String idempotencyKey) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
        if (!reservation.isHeld()) {
            throw new IllegalStateException("reservation not HELD");
        }

        PaymentAttempt attempt;
        try {
            attempt = paymentAttemptRepository.saveAndFlush(
                    PaymentAttempt.requesting(idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            log.info("Idempotency replay: key={}", idempotencyKey);
            var existing = paymentAttemptRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("attempt vanished after conflict"));
            return paymentRepository.findById(existing.getPaymentId())
                    .orElseThrow(() -> new IllegalStateException("payment not found for existing attempt"));
        }

        Payment payment = paymentRepository.save(Payment.request(reservationId, amount));
        attempt.linkPayment(payment.getId());

        gateway.dispatchPaymentCallback(payment.getId());
        return payment;
    }

    /**
     * Stage 4 — outbox INSERT 만 하고 끝. 짧은 tx.
     * 실제 reservation/seat 업데이트는 OutboxWorker 가 처리.
     */
    @Transactional
    public void enqueueCallback(PaymentCallback callback) {
        String payload = "{\"paymentId\":" + callback.paymentId() + ",\"result\":\"" + callback.result() + "\"}";
        OutboxEvent event = OutboxEvent.create(
                "PAYMENT_CALLBACK",
                String.valueOf(callback.paymentId()),
                payload);
        outboxRepository.save(event);
        log.debug("Outbox enqueued: paymentId={} result={}", callback.paymentId(), callback.result());
    }

    /**
     * OutboxWorker 가 호출하는 처리 로직. 별도 tx.
     * affected=0 이면 멱등 no-op 으로 간주 (이미 처리됨 or 만료됨).
     */
    @Transactional
    public void processCallback(PaymentCallback callback) {
        Payment payment = paymentRepository.findById(callback.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("payment not found: " + callback.paymentId()));

        Reservation reservation = reservationRepository.findById(payment.getReservationId())
                .orElseThrow(() -> new IllegalStateException("reservation not found"));

        if ("SUCCESS".equals(callback.result())) {
            int affected = reservationRepository.updateStatusIfCurrent(
                    payment.getReservationId(), ReservationStatus.HELD, ReservationStatus.PAID);

            if (affected == 0) {
                log.warn("Payment success but reservation no longer HELD — refund needed. paymentId={}",
                        payment.getId());
                payment.confirm();
                return;
            }
            payment.confirm();

            long fence = seatLock.currentFence(reservation.getSeatId());
            int seatAffected = seatRepository.casConfirm(reservation.getSeatId(), fence);
            if (seatAffected == 0) {
                log.warn("seat confirm skipped — fence stale or status mismatch. seatId={}",
                        reservation.getSeatId());
            }

        } else {
            int affected = reservationRepository.updateStatusIfCurrent(
                    payment.getReservationId(), ReservationStatus.HELD, ReservationStatus.CANCELLED);
            payment.fail();

            if (affected > 0) {
                long fence = seatLock.currentFence(reservation.getSeatId());
                seatRepository.casRelease(reservation.getSeatId(), fence);
            }
        }
    }
}
