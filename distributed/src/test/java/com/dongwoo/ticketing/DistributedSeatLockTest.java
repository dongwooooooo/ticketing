package com.dongwoo.ticketing;

import com.dongwoo.ticketing.lock.DistributedSeatLock;
import com.dongwoo.ticketing.lock.DistributedSeatLock.LockHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분산 락 정합성 — 같은 좌석 동시 진입 1명 통과.
 *
 * 시나리오:
 *  - BTS R열 1번 좌석에 100명이 동시 클릭.
 *  - 코드: ReservationService.reserve → DistributedSeatLock.acquire (SETNX)
 *  - 1명만 acquire=true, fence INCR 발급. 나머지 99명 acquire=false.
 *  - fence 는 단조 증가 — 직렬 검증.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DistributedSeatLockTest {

    @Autowired
    DistributedSeatLock seatLock;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void singleAcquirerWinsUnderContention() throws Exception {
        long seatId = 9001L;
        int threads = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger(0);
        List<Future<LockHandle>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                LockHandle h = seatLock.acquire(seatId);
                if (h.acquired()) acquired.incrementAndGet();
                return h;
            }));
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // 동시 100건 중 1건만 락 획득
        assertThat(acquired.get()).isEqualTo(1);

        Set<Long> fences = new HashSet<>();
        String winnerHolder = null;
        for (Future<LockHandle> f : futures) {
            LockHandle h = f.get();
            if (h.acquired()) {
                fences.add(h.fence());
                winnerHolder = h.holder();
            }
        }
        assertThat(fences).hasSize(1);
        assertThat(fences.iterator().next()).isEqualTo(1L);

        seatLock.release(seatId, winnerHolder);
    }

    @Test
    void fenceIsMonotonicallyIncreasing() {
        long seatId = 9002L;

        LockHandle h1 = seatLock.acquire(seatId);
        assertThat(h1.acquired()).isTrue();
        assertThat(h1.fence()).isEqualTo(1L);
        seatLock.release(seatId, h1.holder());

        LockHandle h2 = seatLock.acquire(seatId);
        assertThat(h2.acquired()).isTrue();
        assertThat(h2.fence()).isEqualTo(2L);
        seatLock.release(seatId, h2.holder());

        LockHandle h3 = seatLock.acquire(seatId);
        assertThat(h3.acquired()).isTrue();
        assertThat(h3.fence()).isEqualTo(3L);
        seatLock.release(seatId, h3.holder());
    }
}
