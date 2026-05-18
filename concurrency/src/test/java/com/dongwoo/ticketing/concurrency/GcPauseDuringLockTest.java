package com.dongwoo.ticketing.concurrency;

import com.dongwoo.ticketing.TestcontainersConfiguration;
import com.dongwoo.ticketing.repository.SeatRepository;
import com.dongwoo.ticketing.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SCN-9 — JVM G1GC pause가 비관적 락 보유 중 발생할 때 영향 측정.
 *
 * Kleppmann DDIA §8 "fencing token 부재" anti-pattern 의 단일 노드 변형.
 * 락은 살아있지만 보유자가 STW로 멈춘 동안 다른 thread의 대기 시간이 늘어남.
 *
 * 시나리오:
 *   1. winner thread가 SELECT ... FOR UPDATE 로 좌석 락 획득
 *   2. 트랜잭션 내부에서 의도적 heap pressure (200MB 청크 × N회) → G1GC trigger
 *   3. 그 사이 100개 thread가 같은 좌석을 reserve() 시도
 *   4. winner commit 시점에 모든 대기 thread 해제 → 1건만 성공
 *
 * 측정:
 *   - winner reserve 소요 시간 (GC pause 포함)
 *   - GarbageCollectorMXBean total collection time delta + count
 *   - 대기 thread end-to-end 시간 p50/p99/max
 *
 * JVM args 비교는 -DgcPauseMs=500 vs 50 으로 두 번 실행. 결과는 append 모드로 누적.
 *
 * 기존 ReservationService/Repository 코드 변경 없음.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GcPauseDuringLockTest {

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private static final Path OUTPUT = Path.of(
            "/Users/idong-u/d/ticketing/concurrency/scenario-9-gc-pause-output.txt");

    @Test
    @DisplayName("락 보유 중 GC pause → 대기 thread 지연 측정")
    void gc_pause_during_lock_amplifies_wait() throws Exception {
        // V2 seed 의 R 구역 (section_id=2) 첫 좌석. SeatLockConcurrencyTest가 seat 100을 점유하므로 분리.
        // section 1 = VIP(2000석), section 2 = R(8000석). seat id는 BIGSERIAL이라 1부터 시작.
        // 다른 테스트와 충돌 방지 위해 R열 1번 → seat_id = 2001
        Long seatId = 2001L;
        int threadCount = 100;
        String gcPauseMs = System.getProperty("gcPauseMs", "unknown");

        // GC 통계 baseline
        long gcCountBefore = totalGcCount();
        long gcTimeBefore = totalGcTime();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1);
        CountDownLatch winnerStarted = new CountDownLatch(1);
        CountDownLatch winnerReleasing = new CountDownLatch(1);
        CountDownLatch contendersReady = new CountDownLatch(threadCount);
        CountDownLatch contendersDone = new CountDownLatch(threadCount);

        AtomicLong winnerStartNanos = new AtomicLong();
        AtomicLong winnerEndNanos = new AtomicLong();
        List<Long> contenderWaitNanos = Collections.synchronizedList(new ArrayList<>(threadCount));
        AtomicLong successCount = new AtomicLong();
        AtomicLong rejectedCount = new AtomicLong();

        // === winner: 락 잡고 트랜잭션 내부에서 heap pressure → GC pause 유도 ===
        executor.submit(() -> {
            try {
                winnerStartNanos.set(System.nanoTime());
                transactionTemplate.executeWithoutResult(status -> {
                    // 1) SELECT ... FOR UPDATE 로 좌석 락 획득
                    var seat = seatRepository.findByIdForUpdate(seatId).orElseThrow();
                    winnerStarted.countDown();

                    // 2) heap pressure로 GC trigger. 200MB 청크 × 3회 = 600MB (Xmx 512m 한도에서 빈번한 minor/major GC).
                    //    JVM이 OOM 안 나도록 each chunk를 즉시 release (지역 변수만 alive).
                    for (int i = 0; i < 3; i++) {
                        byte[] chunk = new byte[200_000_000];
                        // touch every page to force actual allocation (zeroing)
                        for (int j = 0; j < chunk.length; j += 4096) {
                            chunk[j] = (byte) (j & 0xff);
                        }
                        // release reference, encourage GC on next iteration
                        chunk = null;
                        System.gc(); // hint — G1 may or may not honor immediately
                    }

                    // 3) 좌석 상태만 살짝 변경 후 트랜잭션 종료 (실제 reservation insert는 안 함 — 다른 contender가 성공할 수 있게)
                    //    좌석을 굳이 hold 시키지 않고 잠시 잡고 풀어주는 시나리오 (= GC pause로 락 보유 시간만 길어진 케이스)
                    winnerReleasing.countDown();
                });
                winnerEndNanos.set(System.nanoTime());
            } catch (Exception e) {
                winnerStarted.countDown();
                winnerReleasing.countDown();
                winnerEndNanos.set(System.nanoTime());
                e.printStackTrace();
            }
        });

        // winner가 락 잡을 때까지 대기
        winnerStarted.await(5, TimeUnit.SECONDS);

        // === contenders: 같은 좌석에 100건 동시 reserve ===
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                contendersReady.countDown();
                long t0 = System.nanoTime();
                try {
                    reservationService.reserve(seatId, "contender-" + idx);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    rejectedCount.incrementAndGet();
                } finally {
                    long t1 = System.nanoTime();
                    contenderWaitNanos.add(t1 - t0);
                    contendersDone.countDown();
                }
            });
        }

        contendersReady.await();
        // contenders가 락 대기 큐에 쌓이도록 약간 sleep (winner는 이미 heap pressure 중)
        contendersDone.await(120, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // === 측정 결과 집계 ===
        long winnerDurationMs = (winnerEndNanos.get() - winnerStartNanos.get()) / 1_000_000;
        long gcCountAfter = totalGcCount();
        long gcTimeAfter = totalGcTime();
        long gcCountDelta = gcCountAfter - gcCountBefore;
        long gcTimeDelta = gcTimeAfter - gcTimeBefore;
        long gcAvgMs = gcCountDelta == 0 ? 0 : gcTimeDelta / gcCountDelta;
        long gcMaxApproxMs = gcTimeDelta; // single-test span; max ≈ total delta upper bound

        List<Long> waitMs = contenderWaitNanos.stream()
                .map(n -> n / 1_000_000)
                .sorted()
                .toList();
        long p50 = percentile(waitMs, 50);
        long p99 = percentile(waitMs, 99);
        long max = waitMs.isEmpty() ? 0 : waitMs.get(waitMs.size() - 1);
        long min = waitMs.isEmpty() ? 0 : waitMs.get(0);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        StringBuilder sb = new StringBuilder();
        sb.append("[run @ ").append(stamp).append(" — MaxGCPauseMillis=").append(gcPauseMs).append("]\n");
        sb.append("  GC count delta: ").append(gcCountDelta).append("\n");
        sb.append("  GC total time delta: ").append(gcTimeDelta).append(" ms\n");
        sb.append("  GC avg pause: ").append(gcAvgMs).append(" ms\n");
        sb.append("  GC max upper-bound: ").append(gcMaxApproxMs).append(" ms\n");
        sb.append("  winner reserve duration: ").append(winnerDurationMs).append(" ms (락 보유 + heap pressure 포함)\n");
        sb.append("  contender wait min: ").append(min).append(" ms\n");
        sb.append("  contender wait p50: ").append(p50).append(" ms\n");
        sb.append("  contender wait p99: ").append(p99).append(" ms\n");
        sb.append("  contender wait max: ").append(max).append(" ms\n");
        sb.append("  contender success / rejected: ").append(successCount.get())
                .append(" / ").append(rejectedCount.get()).append("\n");
        sb.append("\n");

        // === 콘솔 + 파일 append ===
        System.out.print(sb);
        appendOutput(sb.toString());
    }

    private static long totalGcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long n = b.getCollectionCount();
            if (n > 0) c += n;
        }
        return c;
    }

    private static long totalGcTime() {
        long t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long n = b.getCollectionTime();
            if (n > 0) t += n;
        }
        return t;
    }

    private static long percentile(List<Long> sortedMs, int p) {
        if (sortedMs.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sortedMs.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sortedMs.size()) idx = sortedMs.size() - 1;
        return sortedMs.get(idx);
    }

    private static synchronized void appendOutput(String text) {
        try {
            if (!Files.exists(OUTPUT)) {
                Files.writeString(OUTPUT,
                        "===== SCENARIO #9 — GC pause 영향 =====\n\n",
                        StandardOpenOption.CREATE_NEW);
            }
            Files.writeString(OUTPUT, text, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("failed to append scenario-9 output: " + e.getMessage());
        }
    }
}
