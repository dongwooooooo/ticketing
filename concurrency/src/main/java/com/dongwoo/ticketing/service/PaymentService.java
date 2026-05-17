package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.domain.*;
import com.dongwoo.ticketing.infra.pgmock.MockPaymentGateway;
import com.dongwoo.ticketing.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 2 Deep Dive 2 & 3.
 *
 * Deep Dive 2 (결제 멱등성):
 *  - PaymentAttempt.idempotency_key UNIQUE constraint + INSERT 시도.
 *  - 동시 100건 같은 key → 1건만 INSERT 성공, 99건은 DataIntegrityViolationException.
 *  - 멱등 hit 시 기존 결제 응답을 그대로 반환 (replay-safe).
 *
 * Deep Dive 3 (만료-결제 경합):
 *  - reservation.updateStatusIfCurrent(id, HELD, PAID) atomic UPDATE.
 *  - affected rows == 1 → 결제 성공, 0 → 이미 만료/취소된 상태 (환불 대상).
 *  - callback 중복 도착도 같은 메커니즘으로 1번만 적용.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final SeatRepository seatRepository;
    private final MockPaymentGateway gateway;

    @Transactional
    public Payment request(Long reservationId, Integer amount, String idempotencyKey) {
        // Reservation 검증 먼저 (HELD 아니면 즉시 reject)
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
        if (!reservation.isHeld()) {
            throw new IllegalStateException("reservation not HELD");
        }

        // INSERT 시도 — UNIQUE constraint가 race 차단
        Payment payment = paymentRepository.save(Payment.request(reservationId, amount));
        try {
            paymentAttemptRepository.saveAndFlush(
                    PaymentAttempt.of(payment.getId(), idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // 같은 idempotency-key 이미 존재 — 기존 응답 반환 (멱등 hit)
            log.info("Idempotency replay: key={}", idempotencyKey);
            var existingAttempt = paymentAttemptRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("attempt vanished after conflict"));
            return paymentRepository.findById(existingAttempt.getPaymentId())
                    .orElseThrow(() -> new IllegalStateException("payment not found for existing attempt"));
        }

        gateway.dispatchPaymentCallback(payment.getId());
        return payment;
    }

    /**
     * PG callback handler — atomic UPDATE로 lost update 차단.
     * callback이 N회 도착해도 첫 1회만 affected rows == 1, 나머지는 0 (no-op).
     */
    @Transactional
    public void handleCallback(PaymentCallback callback) {
        Payment payment = paymentRepository.findById(callback.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("payment not found"));

        if ("SUCCESS".equals(callback.result())) {
            int affected = reservationRepository.updateStatusIfCurrent(
                    payment.getReservationId(), ReservationStatus.HELD, ReservationStatus.PAID);

            if (affected == 0) {
                // 이미 만료/취소된 reservation — 환불 큐 enqueue (본 Lab은 로그만)
                log.warn("Payment success but reservation no longer HELD — refund needed. paymentId={}",
                        payment.getId());
                payment.confirm();  // payment 자체는 confirm (PG 차감 발생) → 별도 환불 처리
                return;
            }

            payment.confirm();
            Seat seat = seatRepository.findByIdForUpdate(reservationByPayment(payment).getSeatId())
                    .orElseThrow(() -> new IllegalStateException("seat not found"));
            seat.confirm();
            seatRepository.save(seat);

        } else {
            int affected = reservationRepository.updateStatusIfCurrent(
                    payment.getReservationId(), ReservationStatus.HELD, ReservationStatus.CANCELLED);
            payment.fail();

            if (affected > 0) {
                Seat seat = seatRepository.findByIdForUpdate(reservationByPayment(payment).getSeatId())
                        .orElseThrow(() -> new IllegalStateException("seat not found"));
                seat.release();
                seatRepository.save(seat);
            }
        }
    }

    private Reservation reservationByPayment(Payment payment) {
        return reservationRepository.findById(payment.getReservationId())
                .orElseThrow(() -> new IllegalStateException("reservation not found"));
    }
}
