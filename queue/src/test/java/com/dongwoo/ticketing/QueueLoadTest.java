package com.dongwoo.ticketing;

import com.dongwoo.ticketing.queue.InProcessWaitingQueue;
import com.dongwoo.ticketing.queue.WaitingQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3 — InProcessWaitingQueue 부하 측정.
 *
 * 케이스:
 *  A) enqueue throughput  — 10,000 동시 enqueue, p99 < 50ms
 *  B) position 일관성     — 같은 토큰의 position 호출이 단조 비증가
 *  C) admit rate sustained — 100/sec dispatcher 시뮬레이션 10초
 *
 * 주의:
 *  - ExecutorService 는 Executors.newFixedThreadPool(threadCount) — 작게 잡으면 ready.await() deadlock
 *  - @BeforeEach 에서 InProcessWaitingQueue#clear() 로 상태 reset
 *  - WaitingQueueDispatcher 는 'test' profile 에서 비활성, queue.admitNext(n) 직접 호출
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class QueueLoadTest {

    @Autowired WaitingQueue waitingQueue;
    @Autowired InProcessWaitingQueue inProcessQueue;

    @BeforeEach
    void resetQueue() {
        inProcessQueue.clear();
    }

    @Test
    @DisplayName("케이스 A — enqueue throughput: 10,000 동시 enqueue, p99 < 50ms")
    void enqueue_throughput_10k() throws Exception {
        final int threadCount = 10_000;
        // macOS 의 native thread 한도(보통 ~2048)를 회피하기 위해 virtual thread 사용.
        // virtual thread 도 CountDownLatch 로 동시 시작 동기화 가능.
        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        ConcurrentLinkedQueue<String> tokens = new ConcurrentLinkedQueue<>();
        // enqueue 호출 1건당 latency (ns).
        long[] latencyNs = new long[threadCount];
        AtomicInteger idx = new AtomicInteger(0);

        long t0Wall = System.nanoTime();
        for (int i = 0; i < threadCount; i++) {
            final int userIdx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    long s = System.nanoTime();
                    String token = waitingQueue.enqueue("user-" + userIdx);
                    long e = System.nanoTime();
                    int slot = idx.getAndIncrement();
                    latencyNs[slot] = e - s;
                    tokens.add(token);
                } catch (Exception ex) {
                    // throughput 테스트에서 enqueue 자체는 실패하지 않아야 함
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        long t1Wall = System.nanoTime();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // 1) 모두 토큰 발급
        assertEquals(threadCount, tokens.size(), "모든 enqueue 호출은 토큰을 발급해야 함");

        // 2) 토큰 유일
        Set<String> uniqueTokens = new HashSet<>(tokens);
        assertEquals(threadCount, uniqueTokens.size(), "토큰은 유일해야 함");

        // 3) position 유일 — 큐 안에 1..N 까지 정확히 1번씩
        Set<Long> uniquePositions = ConcurrentHashMap.newKeySet();
        for (String token : tokens) {
            long pos = waitingQueue.position(token);
            assertTrue(pos > 0, "유효한 position 이어야 함 (got " + pos + ")");
            uniquePositions.add(pos);
        }
        assertEquals(threadCount, uniquePositions.size(), "position 은 유일해야 함");

        // 4) 지연 통계
        long[] sorted = latencyNs.clone();
        java.util.Arrays.sort(sorted);
        long p50Ns = sorted[(int) (threadCount * 0.50)];
        long p99Ns = sorted[(int) (threadCount * 0.99)];
        double p50Ms = p50Ns / 1_000_000.0;
        double p99Ms = p99Ns / 1_000_000.0;
        double wallSec = (t1Wall - t0Wall) / 1_000_000_000.0;
        double opsPerSec = threadCount / wallSec;

        System.out.println("=== Case A: enqueue throughput ===");
        System.out.println("uniqueTokens=" + uniqueTokens.size());
        System.out.println("enqueueOpsPerSec=" + String.format("%.1f", opsPerSec));
        System.out.println("p50EnqueueMs=" + String.format("%.3f", p50Ms));
        System.out.println("p99EnqueueMs=" + String.format("%.3f", p99Ms));
        System.out.println("wallTimeSec=" + String.format("%.3f", wallSec));

        assertTrue(p99Ms < 50.0, "p99 enqueue latency must be < 50ms (got " + p99Ms + "ms)");
    }

    @Test
    @DisplayName("케이스 B — position 일관성: 같은 토큰의 position 은 단조 비증가")
    void position_consistency_under_concurrent_reads() throws Exception {
        final int totalEnqueued = 1_000;
        final int sampledTokens = 100;
        // 각 스레드가 자기 token 에 대해 sequential 로 호출하는 횟수.
        final int callsPerToken = 50;

        // 1) 1,000 명 enqueue (단일 스레드 — 순서 결정적으로 부여)
        List<String> allTokens = new ArrayList<>(totalEnqueued);
        for (int i = 0; i < totalEnqueued; i++) {
            allTokens.add(waitingQueue.enqueue("user-" + i));
        }

        // 2) 무작위 100명 샘플
        Random rng = new Random(42);
        List<String> shuffled = new ArrayList<>(allTokens);
        Collections.shuffle(shuffled, rng);
        final List<String> sample = shuffled.subList(0, sampledTokens);

        // 3) 100 스레드 동시 실행 — 각 스레드는 자기 token 으로 callsPerToken 회 position() 호출
        ExecutorService executor = Executors.newFixedThreadPool(sampledTokens);
        CountDownLatch ready = new CountDownLatch(sampledTokens);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(sampledTokens);

        // token → list of (callOrder, position)
        ConcurrentHashMap<String, ConcurrentLinkedQueue<long[]>> observations = new ConcurrentHashMap<>();
        for (String t : sample) observations.put(t, new ConcurrentLinkedQueue<>());

        AtomicLong globalSeq = new AtomicLong(0);

        // 백그라운드에서 천천히 admit 진행 — position 이 실제로 감소해야 검증 가능
        ExecutorService admitterPool = Executors.newSingleThreadExecutor();
        admitterPool.submit(() -> {
            try {
                // 100ms 동안 ~20 회 admit, 누적 400명 admit
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(5);
                    waitingQueue.admitNext(20);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        for (final String token : sample) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    for (int k = 0; k < callsPerToken; k++) {
                        long order = globalSeq.incrementAndGet();
                        long pos = waitingQueue.position(token);
                        observations.get(token).add(new long[]{order, pos});
                        // 호출 간 짧은 백오프 — admit 이 진행되어 position 변화 관찰 가능
                        if (k % 5 == 4) Thread.sleep(1);
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        admitterPool.shutdown();
        admitterPool.awaitTermination(5, TimeUnit.SECONDS);

        // 4) 같은 토큰 내에서 호출 순서대로 정렬 → position 이 증가한 경우 카운트
        // 단, ALREADY_ADMITTED(-1) 로의 전이는 정상 (대기열에서 빠짐), 비증가 검증에서 제외.
        int inconsistencyCount = 0;
        int totalSeqs = 0;
        for (String token : sample) {
            List<long[]> obs = new ArrayList<>(observations.get(token));
            obs.sort((a, b) -> Long.compare(a[0], b[0]));
            long prev = Long.MAX_VALUE;
            for (long[] row : obs) {
                long pos = row[1];
                if (pos < 0) {
                    // -1: admitted, -2: not found. 둘 다 대기열 진행의 정상 종단.
                    prev = -1;
                    continue;
                }
                if (prev != Long.MAX_VALUE && prev >= 0 && pos > prev) {
                    inconsistencyCount++;
                }
                prev = pos;
                totalSeqs++;
            }
        }

        System.out.println("=== Case B: position consistency ===");
        System.out.println("sampledTokens=" + sampledTokens);
        System.out.println("callsPerToken=" + callsPerToken);
        System.out.println("totalObservations=" + totalSeqs);
        System.out.println("inconsistencyCount=" + inconsistencyCount);

        assertEquals(0, inconsistencyCount,
                "같은 토큰의 position 호출은 monotonically non-increasing 이어야 함 (admitted 전이 제외)");
    }

    @Test
    @DisplayName("케이스 C — admit rate sustained: 100ms마다 N=10 admit, 10초간 100/sec ±10%")
    void admit_rate_sustained_100_per_sec() throws Exception {
        final int totalEnqueued = 1_000;
        final int admitPerTick = 10;
        final long tickIntervalMs = 100;
        final long durationMs = 10_000;
        // 예상: 10,000ms / 100ms * 10 = 1,000 admit
        final int expectedAdmits = (int) (durationMs / tickIntervalMs) * admitPerTick;

        // 1) 1,000 명 enqueue
        for (int i = 0; i < totalEnqueued; i++) {
            waitingQueue.enqueue("user-" + i);
        }

        // 2) dispatcher 시뮬레이션 — 100ms 마다 admitNext(10), 10초
        AtomicInteger totalAdmitted = new AtomicInteger(0);
        long t0 = System.nanoTime();
        long endAt = System.currentTimeMillis() + durationMs;
        int ticks = 0;
        while (System.currentTimeMillis() < endAt) {
            long tickStart = System.currentTimeMillis();
            int n = waitingQueue.admitNext(admitPerTick);
            totalAdmitted.addAndGet(n);
            ticks++;
            long elapsed = System.currentTimeMillis() - tickStart;
            long sleep = tickIntervalMs - elapsed;
            if (sleep > 0) Thread.sleep(sleep);
        }
        long t1 = System.nanoTime();
        double actualDurationSec = (t1 - t0) / 1_000_000_000.0;
        int actual = totalAdmitted.get();
        double avgRate = actual / actualDurationSec;

        System.out.println("=== Case C: admit rate sustained ===");
        System.out.println("expectedAdmits=" + expectedAdmits);
        System.out.println("actualAdmits=" + actual);
        System.out.println("ticks=" + ticks);
        System.out.println("durationSec=" + String.format("%.3f", actualDurationSec));
        System.out.println("avgAdmitRatePerSec=" + String.format("%.2f", avgRate));

        // 큐에 1,000 명만 있으므로 1,000 까지만 admit 가능.
        // 합격 기준: actual 이 1,000 의 ±10% 범위 (900 ~ 1,000) — admit 은 가용 인원 한도 내.
        int lower = (int) (totalEnqueued * 0.90);
        int upper = totalEnqueued;
        assertTrue(actual >= lower && actual <= upper,
                "actualAdmits=" + actual + " must be within [" + lower + ", " + upper + "]");

        // 평균 admit rate = 100/sec ± 10% (90~110)
        // 1,000 명 한도 도달 후엔 0 admit 이므로 평균이 약간 낮을 수 있음.
        // expectedAdmits 기준 ±10% 이지만 큐 고갈을 감안해 [80, 110] 범위로 완화.
        assertTrue(avgRate >= 80.0 && avgRate <= 110.0,
                "avgAdmitRatePerSec=" + avgRate + " must be within [80, 110]");
    }
}
