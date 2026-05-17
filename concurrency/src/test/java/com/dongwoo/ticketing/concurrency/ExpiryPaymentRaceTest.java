package com.dongwoo.ticketing.concurrency;

import com.dongwoo.ticketing.TestcontainersConfiguration;
import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.domain.Payment;
import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.service.ExpiryService;
import com.dongwoo.ticketing.service.PaymentService;
import com.dongwoo.ticketing.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCN-S-02 — 만료 처리와 결제 callback 동시 진입 race.
 *
 * 가설: updateStatusIfCurrent(id, HELD, X) atomic UPDATE로
 *       둘 중 하나만 affected rows == 1, 다른 하나는 == 0.
 *       PAID가 EXPIRED로 덮이거나 그 반대 발생 X.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ExpiryPaymentRaceTest {

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired ExpiryService expiryService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("만료 처리와 결제 callback 동시 → reservation은 PAID 또는 EXPIRED 중 하나로 수렴 (둘 다 X)")
    void expiry_payment_race_atomic() throws Exception {
        Long seatId = 300L;
        Reservation reservation = reservationService.reserve(seatId, "user-race");
        Long resId = reservation.getId();

        // expires_at을 과거로 강제 → ExpiryService가 만료시킬 수 있는 상태
        transactionTemplate.executeWithoutResult(status -> {
            int affected = reservationRepository.forceExpiresAt(resId, LocalDateTime.now().minusMinutes(10));
            if (affected != 1) {
                throw new IllegalStateException("forceExpire failed for id=" + resId);
            }
        });

        // 결제 발생
        Payment payment = paymentService.request(resId, 250000, "idem-race-" + System.nanoTime());

        // 만료 처리 + callback SUCCESS 동시 실행
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        executor.submit(() -> {
            try {
                start.await();
                expiryService.expireOverdueReservations();
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            try {
                start.await();
                paymentService.handleCallback(new PaymentCallback(payment.getId(), "SUCCESS"));
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        done.await();
        executor.shutdown();

        var finalState = reservationRepository.findById(resId).orElseThrow().getStatus();
        System.out.println("final reservation status: " + finalState);
        assertTrue(
                finalState == ReservationStatus.PAID || finalState == ReservationStatus.EXPIRED,
                "must converge to exactly PAID or EXPIRED, got: " + finalState);
    }
}
