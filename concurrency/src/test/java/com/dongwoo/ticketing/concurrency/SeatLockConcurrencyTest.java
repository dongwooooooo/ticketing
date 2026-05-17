package com.dongwoo.ticketing.concurrency;

import com.dongwoo.ticketing.TestcontainersConfiguration;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.repository.ReservationRepository;
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
 * SCN-M-02 — 같은 좌석 동시 100건 예매.
 *
 * 가설: Pessimistic Lock + partial UNIQUE index 조합으로 정확히 1건만 통과.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeatLockConcurrencyTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("좌석 1에 동시 100건 → 정확히 1건만 HELD")
    void seat_lock_blocks_oversell() throws Exception {
        Long seatId = 100L;
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    reservationService.reserve(seatId, "user-" + idx);
                    success.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        long heldCount = reservationRepository.findAll().stream()
                .filter(r -> r.getSeatId().equals(seatId))
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        System.out.println("success=" + success.get() + " rejected=" + rejected.get() + " held=" + heldCount);
        assertEquals(1, success.get(), "exactly 1 reservation must succeed");
        assertEquals(99, rejected.get(), "remaining 99 must be rejected");
        assertEquals(1L, heldCount, "DB must have exactly 1 HELD reservation for the seat");
    }
}
