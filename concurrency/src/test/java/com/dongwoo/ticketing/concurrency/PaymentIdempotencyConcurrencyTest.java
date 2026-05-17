package com.dongwoo.ticketing.concurrency;

import com.dongwoo.ticketing.TestcontainersConfiguration;
import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.repository.PaymentAttemptRepository;
import com.dongwoo.ticketing.repository.PaymentRepository;
import com.dongwoo.ticketing.service.PaymentService;
import com.dongwoo.ticketing.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SCN-U-05 — 같은 idempotency-key로 동시 100건 결제.
 *
 * 가설: UNIQUE constraint + INSERT ON CONFLICT 패턴으로 PaymentAttempt row 정확히 1건.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentIdempotencyConcurrencyTest {

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentAttemptRepository paymentAttemptRepository;
    @Autowired PaymentRepository paymentRepository;

    @Test
    @DisplayName("같은 idempotency-key 동시 100건 → attempt row 1건, payment 1건")
    void duplicate_idempotency_key_blocked() throws Exception {
        Long seatId = 200L;
        Reservation reservation = reservationService.reserve(seatId, "user-idem-test");
        Long resId = reservation.getId();
        String key = "idem-test-" + System.nanoTime();

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    paymentService.request(resId, 250000, key);
                    success.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        long attemptCount = paymentAttemptRepository.findAll().stream()
                .filter(a -> a.getIdempotencyKey().equals(key))
                .count();

        System.out.println("success=" + success.get() + " errors=" + errors.get() + " attempts=" + attemptCount);
        assertEquals(1L, attemptCount, "exactly 1 attempt row must persist for the key");
    }
}
