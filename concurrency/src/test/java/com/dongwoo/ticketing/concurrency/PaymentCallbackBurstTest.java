package com.dongwoo.ticketing.concurrency;

import com.dongwoo.ticketing.TestcontainersConfiguration;
import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.service.PaymentService;
import com.dongwoo.ticketing.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCN-7 — 결제 콜백 폭주 측정.
 *
 * 시나리오:
 *  - 1000명이 결제 완료 → PG가 5초 안에 callback 1000건 전송.
 *  - 우리 서버는 callback을 {@code @Async}로 비동기 처리한다고 가정.
 *  - 기본 상태: {@link SimpleAsyncTaskExecutor} fallback (호출당 thread 생성).
 *  - 개선 상태: {@link ThreadPoolTaskExecutor} core=20 max=50 queue=500.
 *
 * 측정:
 *  - peak thread count (ThreadMXBean.getPeakThreadCount)
 *  - peak heap used (Runtime totalMemory - freeMemory, monitor thread sampling)
 *  - wall time (latch await로 완료 동기화)
 *  - PAID 전이 성공 건수 (reservation.status 카운트)
 *
 * 격리 설계:
 *  - {@code @Async} proxy는 application context 한 번에 하나의 executor만 묶이므로
 *    두 모드를 같은 컨텍스트에서 동시에 비교할 수 없다.
 *  - 본 테스트는 executor 그 자체의 동작 차이를 직접 측정하기 위해
 *    {@link Executor#execute(Runnable)}를 통해 두 executor를 모두 구동한다.
 *    Spring {@code @Async}가 결국 동일한 executor에 위임하므로 결과는 동치다.
 *  - AsyncConfig (@Profile("async-pool"))는 운영용 bean — 본 테스트와 독립.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentCallbackBurstTest {

    private static final int N = 1000;
    /** section 3 (S석)의 seatId 범위 안에서 1000개를 사용. 다른 테스트와 충돌 X. */
    private static final long SEAT_ID_START = 5001L;
    private static final long SEAT_ID_END = SEAT_ID_START + N;

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("1000 callback burst — SimpleAsyncTaskExecutor vs ThreadPoolTaskExecutor")
    void callback_burst_executor_comparison() throws Exception {
        // ---------- PHASE 0: 1000 reservation + payment seed ----------
        List<Long> paymentIds = seedReservationsAndPayments();

        // ---------- PHASE 1: SimpleAsyncTaskExecutor (Spring 기본 fallback) ----------
        BurstResult defaultResult = runBurst(
                "SimpleAsyncTaskExecutor",
                new SimpleAsyncTaskExecutor("simple-async-"),
                paymentIds);

        // ---------- PHASE 1.5: state reset (HELD로 복귀) + thread/heap quiescence ----------
        resetReservationsToHeld();
        waitForThreadQuiescence();

        // ---------- PHASE 2: ThreadPoolTaskExecutor core=20 max=50 queue=500 ----------
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(20);
        pool.setMaxPoolSize(50);
        pool.setQueueCapacity(500);
        pool.setThreadNamePrefix("async-pool-");
        pool.initialize();

        BurstResult poolResult;
        try {
            poolResult = runBurst("ThreadPoolTaskExecutor", pool, paymentIds);
        } finally {
            pool.shutdown();
        }

        // ---------- PHASE 3: 결과 출력 + 파일 기록 ----------
        String report = buildReport(defaultResult, poolResult);
        System.out.println(report);
        Files.writeString(
                Path.of("/Users/idong-u/d/ticketing/concurrency/scenario-7-callback-burst-output.txt"),
                report);

        // [핵심 검증 1] Default executor는 호출당 thread 생성 → peak thread가 maxPoolSize(50)*10배 이상.
        //  - SimpleAsyncTaskExecutor는 thread를 풀링 안 함 (concurrencyLimit=-1 unbounded).
        //  - 1000건 burst 중 일부는 빠르게 끝나 thread가 회수되어 peak는 600~1000 사이 변동.
        //  - 결정적 검증을 위해 500 이상만 요구 (pool의 50개와 10배 이상 차이).
        assertTrue(defaultResult.peakThreadCount >= 500,
                "Default executor가 burst로 thread 폭증해야 함 (peak=" + defaultResult.peakThreadCount + ")");

        // [핵심 검증 2] Pool은 thread 수가 maxPoolSize(50) + 기존 thread 정도로 묶여야 함
        assertTrue(poolResult.peakThreadCount < 200,
                "ThreadPool은 maxPoolSize(50) 이하로 thread 폭증을 차단해야 함 (peak=" + poolResult.peakThreadCount + ")");

        // [핵심 검증 3] Default가 ThreadPool보다 thread/heap 모두 많아야 함
        assertTrue(defaultResult.peakThreadCount > poolResult.peakThreadCount * 5,
                "Default가 Pool보다 5배 이상 thread 사용해야 함 (개선 효과 입증)");

        // [부산물 검증] Default는 1000건 모두 처리됨 (thread 무한 생성으로 reject 없음)
        assertEquals(N, defaultResult.paidCount,
                "Default executor는 thread 무한 생성으로 1000건 모두 처리 (단 thread 폭증 발생)");

        // [부산물 검증] Pool은 queue+pool 용량(550) 초과분이 RejectedExecutionException으로 거부
        //  - core=20 max=50 queue=500 → 1000건 중 약 450건 reject (AbortPolicy 기본).
        //  - 운영에서는 CallerRunsPolicy 또는 queue 키워야 함. 본 spec은 폭증 차단 효과만 입증.
        assertTrue(poolResult.errors > 0 && poolResult.paidCount + poolResult.errors == N,
                "Pool(spec 그대로)은 burst 초과분을 reject — 운영에서는 정책 보강 필요");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * SCN-7 seed: seat 5001..6000에 reservation HELD + payment REQUESTED 생성.
     * SeatLockConcurrencyTest(seat 100), PaymentIdempotencyConcurrencyTest(seat 200),
     * ExpiryPaymentRaceTest(seat 300)와 충돌 X.
     *
     * 주의: paymentService.request()를 쓰지 않음.
     *  - request() 안의 MockPaymentGateway.dispatchPaymentCallback이 @Async라
     *    seed 단계에서 1000개 신규 thread 생성 + RestTemplate 호출 발생.
     *  - Phase 1 측정에 fixture가 만든 thread 잔재가 섞임.
     *  - JdbcTemplate으로 직접 INSERT해서 격리.
     */
    private List<Long> seedReservationsAndPayments() {
        // 잔재 일괄 정리 (이전 실행 잔여 row 제거)
        jdbcTemplate.update("DELETE FROM payment_attempt WHERE payment_id IN " +
                "(SELECT id FROM payment WHERE reservation_id IN " +
                "(SELECT id FROM reservation WHERE seat_id BETWEEN ? AND ?))",
                SEAT_ID_START, SEAT_ID_END - 1);
        jdbcTemplate.update("DELETE FROM payment WHERE reservation_id IN " +
                "(SELECT id FROM reservation WHERE seat_id BETWEEN ? AND ?)",
                SEAT_ID_START, SEAT_ID_END - 1);
        jdbcTemplate.update("DELETE FROM reservation WHERE seat_id BETWEEN ? AND ?",
                SEAT_ID_START, SEAT_ID_END - 1);
        jdbcTemplate.update("UPDATE seat SET status = 'HELD' WHERE id BETWEEN ? AND ?",
                SEAT_ID_START, SEAT_ID_END - 1);

        // bulk INSERT reservation (HELD) — id RETURNING
        List<Long> reservationIds = new ArrayList<>(N);
        for (long seatId = SEAT_ID_START; seatId < SEAT_ID_END; seatId++) {
            Long rid = jdbcTemplate.queryForObject(
                    "INSERT INTO reservation (seat_id, user_id, status, expires_at) " +
                    "VALUES (?, ?, 'HELD', now() + interval '5 minutes') RETURNING id",
                    Long.class, seatId, "user-burst-" + seatId);
            reservationIds.add(rid);
        }

        // bulk INSERT payment (REQUESTED) — id RETURNING
        List<Long> paymentIds = new ArrayList<>(N);
        for (Long rid : reservationIds) {
            Long pid = jdbcTemplate.queryForObject(
                    "INSERT INTO payment (reservation_id, amount, status) " +
                    "VALUES (?, ?, 'REQUESTED') RETURNING id",
                    Long.class, rid, 250_000);
            paymentIds.add(pid);
        }
        return paymentIds;
    }

    /** Phase 1 후 reservation을 다시 HELD로 되돌려 Phase 2가 똑같은 진입 조건을 갖게 함. */
    private void resetReservationsToHeld() {
        jdbcTemplate.update(
                "UPDATE reservation SET status = 'HELD' WHERE seat_id BETWEEN ? AND ?",
                SEAT_ID_START, SEAT_ID_END - 1);
        jdbcTemplate.update(
                "UPDATE seat SET status = 'HELD' WHERE id BETWEEN ? AND ?",
                SEAT_ID_START, SEAT_ID_END - 1);
        jdbcTemplate.update(
                "UPDATE payment SET status = 'REQUESTED', approved_at = NULL " +
                "WHERE reservation_id IN (SELECT id FROM reservation WHERE seat_id BETWEEN ? AND ?)",
                SEAT_ID_START, SEAT_ID_END - 1);
    }

    /**
     * Phase 1 종료 후 dispatcher/handler 스레드가 종료되길 기다린다.
     * SimpleAsyncTaskExecutor의 1회용 스레드들이 lingering 상태로 남으면
     * Phase 2의 peak thread count 측정이 오염된다.
     */
    private void waitForThreadQuiescence() throws InterruptedException {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int prev = threadBean.getThreadCount();
        int stableCount = 0;
        while (System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(500);
            int now = threadBean.getThreadCount();
            if (now <= prev && now < prev + 5) {
                stableCount++;
                if (stableCount >= 3) break;
            } else {
                stableCount = 0;
            }
            prev = now;
        }
        System.out.println("thread quiescence: count=" + threadBean.getThreadCount());
    }

    /**
     * burst 1회 실행 + 측정.
     * caller thread는 단순히 submit만 하고, 실제 handleCallback은 executor 스레드에서 돈다.
     */
    private BurstResult runBurst(String label, Executor executor, List<Long> paymentIds)
            throws InterruptedException {
        System.out.println("\n===== " + label + " burst start =====");

        // GC 후 baseline
        System.gc();
        Thread.sleep(200);
        Runtime rt = Runtime.getRuntime();
        long baselineHeap = rt.totalMemory() - rt.freeMemory();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        threadBean.resetPeakThreadCount();
        long baselineThreads = threadBean.getThreadCount();

        CountDownLatch done = new CountDownLatch(N);
        AtomicInteger errors = new AtomicInteger();
        AtomicLong peakHeap = new AtomicLong(baselineHeap);
        AtomicBoolean monitorStop = new AtomicBoolean(false);

        // sampling monitor — 10ms 간격으로 heap/thread peak 추적
        Thread monitor = new Thread(() -> {
            while (!monitorStop.get()) {
                long used = rt.totalMemory() - rt.freeMemory();
                peakHeap.updateAndGet(prev -> Math.max(prev, used));
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "burst-monitor-" + label);
        monitor.setDaemon(true);
        monitor.start();

        long startNs = System.nanoTime();

        // 1000건을 5초 안에 동시 전송 — submit은 즉시 끝남 (caller thread는 producer 역할)
        for (Long paymentId : paymentIds) {
            try {
                executor.execute(() -> {
                    try {
                        paymentService.handleCallback(new PaymentCallback(paymentId, "SUCCESS"));
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            } catch (Throwable t) {
                // ThreadPool queue/pool 한계 도달 시 RejectedExecutionException 가능
                errors.incrementAndGet();
                done.countDown();
            }
        }

        // 완료 대기 — burst 자체는 5초 내 dispatch, 처리는 그 뒤로도 진행
        boolean completed = done.await(120, TimeUnit.SECONDS);
        long wallNs = System.nanoTime() - startNs;

        monitorStop.set(true);
        monitor.join(500);

        int peakThreads = threadBean.getPeakThreadCount();
        long peakHeapBytes = peakHeap.get();

        // PAID 전이 카운트
        long paid = reservationRepository.findAll().stream()
                .filter(r -> r.getSeatId() >= SEAT_ID_START && r.getSeatId() < SEAT_ID_END)
                .filter(r -> r.getStatus() == ReservationStatus.PAID)
                .count();

        BurstResult result = new BurstResult(
                label,
                peakThreads,
                baselineThreads,
                peakHeapBytes,
                baselineHeap,
                Duration.ofNanos(wallNs).toMillis(),
                (int) paid,
                errors.get(),
                completed);

        System.out.println(result);
        return result;
    }

    private String buildReport(BurstResult def, BurstResult pool) {
        long defHeapMb = def.peakHeapBytes / (1024 * 1024);
        long poolHeapMb = pool.peakHeapBytes / (1024 * 1024);
        double threadRatio = pool.peakThreadCount == 0 ? 0
                : (double) def.peakThreadCount / pool.peakThreadCount;
        double heapRatio = poolHeapMb == 0 ? 0 : (double) defHeapMb / poolHeapMb;

        return String.format("""
                ===== SCENARIO #7 — 결제 콜백 폭주 =====
                [default executor = SimpleAsyncTaskExecutor]
                peak thread count: %d (baseline %d)
                peak heap MB: %d (baseline %d MB)
                wall time ms: %d
                PAID 전이: %d/%d
                errors: %d
                completed within timeout: %b

                [ThreadPoolTaskExecutor core=20 max=50 queue=500]
                peak thread count: %d (baseline %d)
                peak heap MB: %d (baseline %d MB)
                wall time ms: %d
                PAID 전이: %d/%d
                errors: %d
                completed within timeout: %b

                차이: 스레드 %.1f배 감소 (%d → %d), 메모리 %.1f배 차이 (%d MB → %d MB).
                =======================================
                """,
                def.peakThreadCount, def.baselineThreads,
                defHeapMb, def.baselineHeapBytes / (1024 * 1024),
                def.wallTimeMs,
                def.paidCount, N,
                def.errors,
                def.completed,
                pool.peakThreadCount, pool.baselineThreads,
                poolHeapMb, pool.baselineHeapBytes / (1024 * 1024),
                pool.wallTimeMs,
                pool.paidCount, N,
                pool.errors,
                pool.completed,
                threadRatio, def.peakThreadCount, pool.peakThreadCount,
                heapRatio, defHeapMb, poolHeapMb);
    }

    /** 측정 결과 묶음. */
    private record BurstResult(
            String label,
            int peakThreadCount,
            long baselineThreads,
            long peakHeapBytes,
            long baselineHeapBytes,
            long wallTimeMs,
            int paidCount,
            int errors,
            boolean completed) {

        @Override
        public String toString() {
            return String.format(
                    "%s: peakThreads=%d (baseline %d) peakHeap=%d MB wallMs=%d PAID=%d errors=%d completed=%b",
                    label, peakThreadCount, baselineThreads,
                    peakHeapBytes / (1024 * 1024), wallTimeMs,
                    paidCount, errors, completed);
        }
    }
}
