package com.dongwoo.ticketing.concurrency;

import com.dongwoo.ticketing.TestcontainersConfiguration;
import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.domain.SeatStatus;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.repository.SeatRepository;
import com.dongwoo.ticketing.service.ReservationMetrics;
import com.dongwoo.ticketing.service.ReservationService;
import com.dongwoo.ticketing.service.ReservationService.SeatNotAvailableException;
import com.dongwoo.ticketing.service.SoldOutCache;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCN-#10 — 매진 후 봇 트래픽으로 정상 사용자 응답 시간이 죽는 시나리오.
 *
 * 시나리오:
 *  - 11:01:00 매진. 좌석 1~100을 미리 SOLD/HELD로 만들어 매진 상태 시뮬레이션.
 *  - 30초간 ~5000 TPS 부하로 봇이 매진 좌석 반복 클릭.
 *  - 별도 스레드 1개가 새로 풀린 좌석(101, AVAILABLE)을 10초마다 한번씩 잡으려 시도.
 *  - 정상 사용자의 p99 응답시간을 측정.
 *
 * 두 모드 비교:
 *  1) fast path 부재 (기본) — 모든 봇 요청이 SELECT FOR UPDATE까지 도달.
 *  2) fast path 적용 — SoldOutCache가 application 레벨에서 즉시 거절.
 *
 * 결과는 scenario-10-soldout-bot-output.txt에 누적 기록.
 *
 * 주의 — maxVUs 제한:
 *  - 5000 TPS는 호스트에 부담. 실제로는 가상 사용자 300명 × 짧은 sleep으로 분산.
 *  - 핵심은 "정상 사용자가 봇 트래픽에 묻혀 응답이 죽는지"이므로 비례 효과만 입증.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SoldOutBotTrafficTest {

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SoldOutCache soldOutCache;
    @Autowired ReservationMetrics metrics;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired DataSource dataSource;

    private static final int SOLD_OUT_SEAT_COUNT = 100;
    private static final long FREED_SEAT_ID = 200L;     // 새로 풀린 좌석
    private static final long SOLD_OUT_BASE_ID = 1L;    // 매진 좌석 1~100
    private static final int BOT_VUS = 200;             // 가상 사용자 (호스트 부담 ↓)
    private static final long DURATION_MS = 10_000L;    // 10초 부하 (CI 시간 절약)
    private static final int LEGIT_USER_ATTEMPTS = 8;
    private static final long LEGIT_USER_INTERVAL_MS = 1_200L;
    // fast-path 모드에서 무한 루프 폭주 방지용 — 워커당 100ns sleep
    private static final long BOT_WORKER_NANOS_BUDGET = 50_000L;

    private static final Path OUTPUT_PATH = Path.of(
            System.getProperty("user.dir"),
            "scenario-10-soldout-bot-output.txt");

    @Test
    @DisplayName("매진 후 봇 트래픽 — fast path 부재 vs 적용 (정상 사용자 응답 비교)")
    void bot_traffic_with_and_without_fast_path() throws Exception {
        truncateOutput();
        appendHeader();

        // === Pass 1: fast path OFF ===
        Result baseline = runScenario(false);
        appendResult("fast path 부재 — 모든 요청 DB 도달", baseline);

        // === Pass 2: fast path ON ===
        Result fastPath = runScenario(true);
        appendResult("fast path 적용 — application 차단", fastPath);

        appendComparison(baseline, fastPath);

        // 가설 검증: fast path 적용 시 정상 사용자 p99가 baseline보다 빠르거나 같다.
        // CI 환경 jitter 흡수를 위해 hard assertion은 "DB hit 차이"만 검증.
        assertTrue(fastPath.dbHits < baseline.dbHits,
                "fast path 적용 시 DB hit 수가 감소해야 함. baseline=" + baseline.dbHits
                        + " fastPath=" + fastPath.dbHits);
        // 정상 사용자가 한 번이라도 좌석 잡았어야 함 (전체 실패면 측정 의미 없음)
        assertTrue(baseline.legitSuccessCount + fastPath.legitSuccessCount > 0,
                "두 모드 합쳐 정상 사용자 성공이 1건 이상이어야 함");
    }

    private Result runScenario(boolean fastPathEnabled) throws Exception {
        // 환경 리셋
        ReflectionTestUtils.setField(reservationService, "fastPathEnabled", fastPathEnabled);
        soldOutCache.clear();
        metrics.reset();

        // 좌석 SOLD_OUT_BASE_ID ~ +100을 SOLD로, 좌석 200을 AVAILABLE로 강제 리셋
        // (이전 테스트 잔여 상태 제거)
        transactionTemplate.executeWithoutResult(status -> {
            for (long id = SOLD_OUT_BASE_ID; id < SOLD_OUT_BASE_ID + SOLD_OUT_SEAT_COUNT; id++) {
                Seat seat = seatRepository.findById(id).orElse(null);
                if (seat == null) continue;
                ReflectionTestUtils.setField(seat, "status", SeatStatus.SOLD);
                seatRepository.save(seat);
            }
            Seat freed = seatRepository.findById(FREED_SEAT_ID).orElse(null);
            if (freed != null) {
                ReflectionTestUtils.setField(freed, "status", SeatStatus.AVAILABLE);
                seatRepository.save(freed);
            }
            // 이전 pass에서 만든 좌석 200의 reservation 정리
            reservationRepository.findAll().stream()
                    .filter(r -> r.getSeatId().equals(FREED_SEAT_ID))
                    .forEach(reservationRepository::delete);
        });

        // fast path 모드면 매진 좌석을 미리 캐시에 등록
        if (fastPathEnabled) {
            for (long id = SOLD_OUT_BASE_ID; id < SOLD_OUT_BASE_ID + SOLD_OUT_SEAT_COUNT; id++) {
                soldOutCache.markSoldOut(id);
            }
        }

        // 봇 트래픽 + 정상 사용자 동시 실행
        ExecutorService botPool = Executors.newFixedThreadPool(BOT_VUS);
        ExecutorService legitPool = Executors.newSingleThreadExecutor();
        ExecutorService monitorPool = Executors.newSingleThreadExecutor();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong botRequests = new AtomicLong();
        AtomicLong botSoldOutRejects = new AtomicLong();
        AtomicLong botOtherErrors = new AtomicLong();
        Semaphore tpsLimiter = new Semaphore(BOT_VUS); // 동시 진행 상한

        // HikariCP 피크 모니터
        HikariPoolMXBean poolMx = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        AtomicInteger activePeak = new AtomicInteger();
        AtomicInteger pendingPeak = new AtomicInteger();

        monitorPool.submit(() -> {
            while (!stop.get()) {
                int active = poolMx.getActiveConnections();
                int pending = poolMx.getThreadsAwaitingConnection();
                activePeak.updateAndGet(prev -> Math.max(prev, active));
                pendingPeak.updateAndGet(prev -> Math.max(prev, pending));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        long startNanos = System.nanoTime();
        long endNanos = startNanos + DURATION_MS * 1_000_000L;

        // 봇 트래픽 발사 — BOT_VUS 만큼 워커 spawn, 각 워커는 매진 좌석 한 개 골라 반복 호출
        List<CountDownLatch> readyLatches = new ArrayList<>();
        for (int v = 0; v < BOT_VUS; v++) {
            final int workerId = v;
            CountDownLatch readyLatch = new CountDownLatch(1);
            readyLatches.add(readyLatch);
            botPool.submit(() -> {
                readyLatch.countDown();
                long seatPick = SOLD_OUT_BASE_ID + (workerId % SOLD_OUT_SEAT_COUNT);
                while (System.nanoTime() < endNanos && !stop.get()) {
                    botRequests.incrementAndGet();
                    try {
                        reservationService.reserve(seatPick, "bot-" + workerId);
                    } catch (SeatNotAvailableException expected) {
                        botSoldOutRejects.incrementAndGet();
                    } catch (Exception other) {
                        botOtherErrors.incrementAndGet();
                    }
                    // fast-path 모드에서 워커당 무제한 루프 → 1M+ ops/sec → log/heap 폭주.
                    // 짧은 park로 ~20K ops/sec/worker로 제한 (전체 약 4M ops/15s).
                    java.util.concurrent.locks.LockSupport.parkNanos(BOT_WORKER_NANOS_BUDGET);
                }
            });
        }

        // 정상 사용자 — 좌석 200 시도. 매번 새 사용자 ID 사용 (uq_reservation_seat_active 회피용 reset).
        AtomicInteger legitSuccess = new AtomicInteger();
        AtomicInteger legitFailure = new AtomicInteger();
        List<Long> legitLatenciesMs = Collections.synchronizedList(new ArrayList<>());

        legitPool.submit(() -> {
            // 봇 워커가 모두 준비됐는지 확인
            for (CountDownLatch r : readyLatches) {
                try {
                    r.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            for (int i = 0; i < LEGIT_USER_ATTEMPTS && System.nanoTime() < endNanos; i++) {
                try {
                    Thread.sleep(LEGIT_USER_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // 매번 좌석을 AVAILABLE로 리셋 + 캐시에서 release + 활성 reservation 제거
                // ("환불로 풀린 좌석" 시뮬레이션 — partial UNIQUE index가 막지 않도록 정리)
                try {
                    transactionTemplate.executeWithoutResult(s -> {
                        Seat freed = seatRepository.findById(FREED_SEAT_ID).orElse(null);
                        if (freed != null) {
                            ReflectionTestUtils.setField(freed, "status", SeatStatus.AVAILABLE);
                            seatRepository.save(freed);
                        }
                        // HELD reservation 정리 (partial UNIQUE 회피)
                        reservationRepository.findAll().stream()
                                .filter(r -> r.getSeatId().equals(FREED_SEAT_ID))
                                .forEach(reservationRepository::delete);
                    });
                    soldOutCache.release(FREED_SEAT_ID);
                } catch (Exception ignored) {
                    // reset 실패해도 측정 진행
                }
                long t0 = System.nanoTime();
                try {
                    reservationService.reserve(FREED_SEAT_ID, "legit-user-" + i);
                    legitSuccess.incrementAndGet();
                } catch (Exception e) {
                    legitFailure.incrementAndGet();
                } finally {
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                    legitLatenciesMs.add(elapsedMs);
                }
                // 다음 라운드에서 다시 reserve 가능하도록 reservation 정리는 불필요
                // (uq_reservation_seat_active는 HELD/PAID 활성 1건만 막음 — 첫 성공 후 두 번째 시도부터 막힘)
                // → 실용적으로 매 시도마다 강제 status 리셋만 하면 첫 lock 까진 통과.
                //   그 다음은 partial unique index에 막힘. 측정 의미상 "최초 진입 latency"가 핵심.
            }
        });

        // 종료 대기
        botPool.shutdown();
        legitPool.shutdown();
        botPool.awaitTermination(DURATION_MS + 5_000L, TimeUnit.MILLISECONDS);
        legitPool.awaitTermination(DURATION_MS + 10_000L, TimeUnit.MILLISECONDS);
        stop.set(true);
        monitorPool.shutdown();
        monitorPool.awaitTermination(2, TimeUnit.SECONDS);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        Result r = new Result();
        r.botRequests = botRequests.get();
        r.botSoldOutRejects = botSoldOutRejects.get();
        r.botOtherErrors = botOtherErrors.get();
        r.botThroughput = r.botRequests * 1000.0 / Math.max(1, elapsedMs);
        r.elapsedMs = elapsedMs;
        r.legitSuccessCount = legitSuccess.get();
        r.legitFailureCount = legitFailure.get();
        r.legitLatenciesMs = new ArrayList<>(legitLatenciesMs);
        r.activePeak = activePeak.get();
        r.pendingPeak = pendingPeak.get();
        r.serviceCalls = metrics.serviceCalls();
        r.dbHits = metrics.dbHits();
        r.fastPathRejects = metrics.fastPathRejects();
        r.fastPathEnabled = fastPathEnabled;
        return r;
    }

    // === 결과 카드 + 출력 ===

    private static class Result {
        long botRequests;
        long botSoldOutRejects;
        long botOtherErrors;
        double botThroughput;
        long elapsedMs;
        int legitSuccessCount;
        int legitFailureCount;
        List<Long> legitLatenciesMs;
        int activePeak;
        int pendingPeak;
        long serviceCalls;
        long dbHits;
        long fastPathRejects;
        boolean fastPathEnabled;

        long legitP99() {
            if (legitLatenciesMs.isEmpty()) return -1;
            List<Long> sorted = new ArrayList<>(legitLatenciesMs);
            Collections.sort(sorted);
            int idx = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.99) - 1);
            if (idx < 0) idx = 0;
            return sorted.get(idx);
        }

        long legitAvg() {
            if (legitLatenciesMs.isEmpty()) return -1;
            return (long) legitLatenciesMs.stream().mapToLong(Long::longValue).average().orElse(-1);
        }

        long legitMax() {
            return legitLatenciesMs.stream().mapToLong(Long::longValue).max().orElse(-1);
        }
    }

    private void truncateOutput() throws IOException {
        Files.writeString(OUTPUT_PATH, "");
    }

    private void appendHeader() throws IOException {
        String header = "===== SCENARIO #10 — 매진 후 봇 트래픽 =====\n"
                + "측정 설정: 봇 VUs=" + BOT_VUS
                + ", 부하 지속=" + DURATION_MS + "ms"
                + ", 매진 좌석=" + SOLD_OUT_SEAT_COUNT
                + ", 정상 사용자 시도=" + LEGIT_USER_ATTEMPTS
                + " (interval " + LEGIT_USER_INTERVAL_MS + "ms)\n"
                + "Hikari maximum-pool-size=10 (application.yml 기준)\n"
                + "============================================\n\n";
        Files.writeString(OUTPUT_PATH, header,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void appendResult(String label, Result r) throws IOException {
        long dbRate = (long) (r.dbHits * 1000.0 / Math.max(1, r.elapsedMs));
        String section = "[" + label + "]\n"
                + "  매진 좌석 throughput: " + String.format("%.1f", r.botThroughput) + " ops/sec\n"
                + "  봇 총 요청: " + r.botRequests
                + " (sold-out reject=" + r.botSoldOutRejects
                + ", other err=" + r.botOtherErrors + ")\n"
                + "  정상 사용자 (좌석 " + FREED_SEAT_ID + ") avg: " + r.legitAvg() + "ms"
                + ", p99: " + r.legitP99() + "ms"
                + ", max: " + r.legitMax() + "ms"
                + " (success=" + r.legitSuccessCount + "/" + (r.legitSuccessCount + r.legitFailureCount) + ")\n"
                + "  HikariCP active peak: " + r.activePeak + "/10"
                + ", pending peak: " + r.pendingPeak + "\n"
                + "  DB query rate (reserve 진입): " + dbRate + "/sec"
                + " (totalDbHits=" + r.dbHits
                + ", serviceCalls=" + r.serviceCalls
                + ", fastPathRejects=" + r.fastPathRejects + ")\n\n";
        Files.writeString(OUTPUT_PATH, section,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        // stdout 미러
        System.out.print(section);
    }

    private void appendComparison(Result baseline, Result fastPath) throws IOException {
        long baseP99 = baseline.legitP99();
        long fastP99 = fastPath.legitP99();
        double speedup = baseP99 > 0 && fastP99 > 0
                ? (double) baseP99 / Math.max(1, fastP99)
                : -1.0;
        long dbReduction = baseline.dbHits == 0 ? 0
                : (baseline.dbHits - fastPath.dbHits) * 100 / baseline.dbHits;
        String summary = "============================================\n"
                + "영향 요약:\n"
                + "  정상 사용자 p99 — baseline " + baseP99 + "ms vs fast-path " + fastP99 + "ms"
                + " (speedup x" + String.format("%.2f", speedup) + ")\n"
                + "  DB hit 감소율: " + dbReduction + "% (baseline " + baseline.dbHits
                + " → fast-path " + fastPath.dbHits + ")\n"
                + "  HikariCP active peak — baseline " + baseline.activePeak
                + " vs fast-path " + fastPath.activePeak + "\n"
                + "============================================\n";
        Files.writeString(OUTPUT_PATH, summary,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.print(summary);
    }
}
