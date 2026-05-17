package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.domain.*;
import com.dongwoo.ticketing.infra.pgmock.MockPaymentGateway;
import com.dongwoo.ticketing.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 1 naive 결제 처리.
 *
 * 의도적 결함 (Stage 2에서 해결):
 *  - PaymentAttempt에 idempotency_key UNIQUE 없음. 같은 key 동시 요청 시 모두 통과.
 *  - findFirstByIdempotencyKey + INSERT는 read-then-write race.
 *  - Callback이 N회 도착하면 N번 confirm 시도. Payment 상태 변경이 N회.
 *  - 만료 처리와 callback 동시 진입 시 lost update.
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
        // Stage 1 naive: race condition 의도. 동시 요청 시 둘 다 "key 없음" 보고 둘 다 INSERT.
        var existing = paymentAttemptRepository.findFirstByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotency hit (naive — race condition risk): key={}", idempotencyKey);
            Long paymentId = existing.get().getPaymentId();
            return paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new IllegalStateException("payment not found for existing attempt"));
        }

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
        if (!reservation.isHeld()) {
            throw new IllegalStateException("reservation not HELD");
        }

        Payment payment = paymentRepository.save(Payment.request(reservationId, amount));
        paymentAttemptRepository.save(PaymentAttempt.of(payment.getId(), idempotencyKey));

        gateway.dispatchPaymentCallback(payment.getId());

        return payment;
    }

    /**
     * Stage 1 naive callback handler. 중복 호출 방어 없음.
     * 같은 paymentId로 callback이 N회 오면 N번 confirm() 호출 + N번 seat SOLD 시도.
     */
    @Transactional
    public void handleCallback(PaymentCallback callback) {
        Payment payment = paymentRepository.findById(callback.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("payment not found"));

        if ("SUCCESS".equals(callback.result())) {
            payment.confirm();

            Reservation reservation = reservationRepository.findById(payment.getReservationId())
                    .orElseThrow(() -> new IllegalStateException("reservation not found"));
            // Stage 1 naive: 만료 처리와 동시 진입 시 lost update 가능
            reservation.markPaid();

            Seat seat = seatRepository.findById(reservation.getSeatId())
                    .orElseThrow(() -> new IllegalStateException("seat not found"));
            seat.confirm();
            seatRepository.save(seat);
        } else {
            payment.fail();

            Reservation reservation = reservationRepository.findById(payment.getReservationId())
                    .orElseThrow(() -> new IllegalStateException("reservation not found"));
            reservation.markCancelled();

            Seat seat = seatRepository.findById(reservation.getSeatId())
                    .orElseThrow(() -> new IllegalStateException("seat not found"));
            seat.release();
            seatRepository.save(seat);
        }
    }
}
