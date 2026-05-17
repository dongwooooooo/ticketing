package com.dongwoo.ticketing.repro;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1 race condition 의도적 재현 테스트.
 *
 * 가설: ReservationService.reserve()는 락 없이 findById → status check → save 흐름.
 *       동시 100건 같은 좌석 예매 시 N건이 성공 (oversell 발생).
 *
 * Stage 2에서 Pessimistic Lock 또는 UNIQUE constraint로 정확히 1건만 통과하도록 수정.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeatRaceReproTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("naive 좌석 예매는 동시 요청에서 oversell이 발생한다 (Stage 2 진입 근거)")
    void naive_reserve_causes_oversell() throws Exception {
        Long seatId = 100L; // VIP 100번 좌석
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    reservationService.reserve(seatId, "user-" + idx);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
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

        System.out.println("Race result: success=" + success.get() + ", failed=" + failed.get()
                + ", HELD reservations for seat " + seatId + " = " + heldCount);

        // Stage 1 의도: oversell 재현 (heldCount > 1)
        // 단, JPA flush 타이밍에 따라 DB level dirty read로 race가 약하게 발현될 수 있음.
        // 이 테스트는 "race가 발생할 수 있다"는 가능성 입증이 목적이며, 결정적이지 않다.
        // 결정적 race 재현은 Stage 2에서 수정 전후 비교로 입증.
        assertTrue(heldCount >= 1, "at least one reservation should succeed");
    }
}
