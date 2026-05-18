package com.dongwoo.ticketing.concurrency;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCN-DB-12 — DB master 장애 시 in-flight 트랜잭션 영향.
 *
 * 시나리오: A가 좌석 1 선점 트랜잭션 진행 중(SELECT FOR UPDATE + INSERT 후 commit 직전) PG가 죽음.
 * 가설:
 *   1) 커밋 전 죽으면 A의 트랜잭션은 commit되지 않는다 → reservation 행은 미존재.
 *   2) A에는 SQLException(connection broken) 전파.
 *   3) standby 승격 후(같은 데이터로 재기동), C가 같은 좌석 재시도 → 성공.
 *      partial UNIQUE 인덱스가 최종 방어선으로 작동(이미 활성 row가 없으므로 충돌 없이 통과).
 *
 * 주의:
 *   - 이 테스트는 Spring context 없이 PostgreSQLContainer를 직접 띄운다.
 *   - 다른 docker 워크로드는 손대지 않는다 (이 테스트가 만든 컨테이너 ID만 kill).
 *   - 같은 컨테이너를 docker start로 재기동 → 데이터 볼륨 보존 → standby가 WAL을 이어받은 상태와 동등.
 */
class DbFailoverTest {

    /** 트랜잭션 인플라이트 상태로 두는 시간. 이 안에 kill을 친다. */
    private static final long INFLIGHT_HOLD_MS = 4_000L;
    /** kill 후 즉시 재기동까지 보조 대기. */
    private static final long RESTART_SETTLE_MS = 3_000L;

    @Test
    @DisplayName("master kill → in-flight TX rollback (no reservation row) → 재기동 후 같은 좌석 재시도 성공")
    void master_kill_during_inflight_reservation() throws Exception {
        PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withDatabaseName("ticketing")
                .withUsername("ticketing")
                .withPassword("ticketing");
        pg.start();

        StringBuilder report = new StringBuilder();
        report.append("===== SCENARIO #12 — DB master 장애 =====\n");

        String containerId = pg.getContainerId();
        assertNotNull(containerId, "container id must be present");

        // 스키마 + seed (concurrency 모듈의 V1/V3 핵심 구조만 발췌)
        try (Connection conn = newConnection(pg)) {
            initSchema(conn);
            insertSeat(conn, 1L);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch txInflight = new CountDownLatch(1);
        AtomicReference<Throwable> threadAError = new AtomicReference<>();
        AtomicReference<String> threadAResult = new AtomicReference<>("UNKNOWN");

        // Thread A: 트랜잭션 시작 → FOR UPDATE → INSERT → 미커밋 sleep → commit 시도
        executor.submit(() -> {
            try (Connection conn = newConnection(pg)) {
                conn.setAutoCommit(false);

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, status FROM seat WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, 1L);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE seat SET status='HELD' WHERE id=?")) {
                    ps.setLong(1, 1L);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reservation (seat_id, user_id, status, expires_at) VALUES (?, ?, 'HELD', ?)")) {
                    ps.setLong(1, 1L);
                    ps.setString(2, "user-A");
                    ps.setObject(3, LocalDateTime.now().plus(Duration.ofMinutes(5)));
                    ps.executeUpdate();
                }
                // 인플라이트 신호 → kill 트리거
                txInflight.countDown();
                // 미커밋 상태로 hold — 이 사이에 master kill
                try {
                    Thread.sleep(INFLIGHT_HOLD_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                try {
                    conn.commit();
                    threadAResult.set("COMMIT_SUCCEEDED");
                } catch (SQLException commitEx) {
                    threadAResult.set("COMMIT_FAILED: " + commitEx.getClass().getSimpleName()
                            + " — " + nullSafe(commitEx.getMessage()));
                    threadAError.set(commitEx);
                }
            } catch (Throwable t) {
                threadAResult.set("CONN_OR_TX_FAILED: " + t.getClass().getSimpleName()
                        + " — " + nullSafe(t.getMessage()));
                threadAError.set(t);
                txInflight.countDown(); // killer 영구 대기 방지
            }
        });

        // Thread B: 인플라이트 진입 신호 받으면 컨테이너 kill
        executor.submit(() -> {
            try {
                txInflight.await();
                Thread.sleep(500); // A가 INSERT 직후 sleep 진입할 여유
                killContainer(containerId);
            } catch (Exception ignored) {
            }
        });

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // 컨테이너 재기동 (같은 데이터 디렉터리 → standby 승격과 동등)
        Thread.sleep(500);
        startContainer(containerId);
        Thread.sleep(RESTART_SETTLE_MS);
        // 재기동 후 호스트 포트가 바뀜 — 새 매핑 조회
        int mappedPort = inspectMappedPort(containerId);
        String jdbcUrl = "jdbc:postgresql://localhost:" + mappedPort + "/" + pg.getDatabaseName();
        String user = pg.getUsername();
        String pass = pg.getPassword();
        waitForReady(jdbcUrl, user, pass);

        // master kill 시점 결과 기록
        report.append("[master kill 시점]\n");
        report.append("thread A 결과: ").append(threadAResult.get()).append("\n");

        // 재기동 후: reservation 행 수 확인
        int reservationRowsAfter;
        String seatStatusAfter;
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass)) {
            reservationRowsAfter = countReservations(conn, 1L);
            seatStatusAfter = readSeatStatus(conn, 1L);
        }
        report.append("in-flight reservation: ")
                .append(reservationRowsAfter == 0 ? "미존재" : "존재(" + reservationRowsAfter + "행)")
                .append("\n");
        report.append("seat status: ").append(seatStatusAfter).append("\n\n");

        // Thread C: 재기동 후 같은 좌석 재시도
        boolean threadCSuccess;
        String threadCError = null;
        boolean partialUniqueViolated = false;
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass)) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM seat WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, 1L);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE seat SET status='HELD' WHERE id=?")) {
                    ps.setLong(1, 1L);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reservation (seat_id, user_id, status, expires_at) VALUES (?, ?, 'HELD', ?)")) {
                    ps.setLong(1, 1L);
                    ps.setString(2, "user-C");
                    ps.setObject(3, LocalDateTime.now().plus(Duration.ofMinutes(5)));
                    ps.executeUpdate();
                }
                conn.commit();
                threadCSuccess = true;
            } catch (SQLException retryEx) {
                conn.rollback();
                threadCSuccess = false;
                threadCError = retryEx.getClass().getSimpleName() + " — " + nullSafe(retryEx.getMessage());
                String sqlState = retryEx.getSQLState();
                // 23505 = unique_violation → partial UNIQUE 인덱스 위반
                if ("23505".equals(sqlState)) {
                    partialUniqueViolated = true;
                }
            }
        }

        // 재시도 후 상태
        int reservationRowsFinal;
        String seatStatusFinal;
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass)) {
            reservationRowsFinal = countReservations(conn, 1L);
            seatStatusFinal = readSeatStatus(conn, 1L);
        }

        report.append("[재시작 후 thread C 재시도]\n");
        report.append("재시도 결과: ").append(threadCSuccess ? "success" : "fail (" + threadCError + ")").append("\n");
        report.append("partial UNIQUE 위반: ").append(partialUniqueViolated ? "yes" : "no").append("\n");
        report.append("재시도 후 reservation 행수: ").append(reservationRowsFinal).append("\n");
        report.append("재시도 후 seat status: ").append(seatStatusFinal).append("\n\n");

        report.append("영향: 사용자 A 는 30초 spinner 후 에러. ")
                .append("좌석 1 상태 = ").append(seatStatusAfter).append(" → 재시도 후 ").append(seatStatusFinal).append(". ")
                .append("재시도 가능 여부 = ").append(threadCSuccess ? "yes" : "no").append(".\n");
        report.append("=======================================\n");

        // 결과 파일 기록
        Path outPath = Path.of("/Users/idong-u/d/ticketing/concurrency/scenario-12-db-failover-output.txt");
        Files.writeString(outPath, report.toString());
        System.out.println(report);

        // 컨테이너 정리 — 우리가 restart 시켰으므로 Docker API로 직접 종료/삭제
        try {
            DockerClient client = DockerClientFactory.lazyClient();
            try {
                client.stopContainerCmd(containerId).withTimeout(5).exec();
            } catch (Exception ignored) {
            }
            try {
                client.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }

        // 검증
        assertNotNull(threadAError.get(), "thread A must observe a failure (commit broken or rollback)");
        assertEquals(0, reservationRowsAfter,
                "master kill 시 commit 미완료 → reservation 행은 0이어야 함 (가설 1 입증)");
        assertTrue(threadCSuccess,
                "재기동 후 동일 좌석 재시도는 성공해야 함 (가설 3 입증)");
        assertEquals(1, reservationRowsFinal, "재시도 후 정확히 1건 HELD 보장");
    }

    // --- 헬퍼들 -----------------------------------------------------------

    private static Connection newConnection(PostgreSQLContainer<?> pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE seat (
                    id BIGSERIAL PRIMARY KEY,
                    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
                    updated_at TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
            st.execute("""
                CREATE TABLE reservation (
                    id BIGSERIAL PRIMARY KEY,
                    seat_id BIGINT NOT NULL,
                    user_id VARCHAR(64) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
            // partial UNIQUE — V3 마이그레이션과 동일
            st.execute("""
                CREATE UNIQUE INDEX uq_reservation_seat_active
                    ON reservation (seat_id)
                    WHERE status IN ('HELD','PAID')
                """);
        }
    }

    private static void insertSeat(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO seat (id, status) VALUES (?, 'AVAILABLE')")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static int countReservations(Connection conn, long seatId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM reservation WHERE seat_id = ? AND status IN ('HELD','PAID')")) {
            ps.setLong(1, seatId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String readSeatStatus(Connection conn, long seatId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM seat WHERE id = ?")) {
            ps.setLong(1, seatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "MISSING";
                return rs.getString(1);
            }
        }
    }

    private static void killContainer(String id) {
        DockerClient client = DockerClientFactory.lazyClient();
        // SIGKILL 즉시 종료 — master crash 시뮬레이션
        client.killContainerCmd(id).withSignal("KILL").exec();
    }

    private static void startContainer(String id) {
        DockerClient client = DockerClientFactory.lazyClient();
        client.startContainerCmd(id).exec();
    }

    /** 재기동 직후 connection refused가 잠시 나올 수 있어 폴링. */
    private static void waitForReady(String jdbcUrl, String user, String pass) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        SQLException lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = DriverManager.getConnection(jdbcUrl, user, pass);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                if (rs.next()) return;
            } catch (SQLException e) {
                lastError = e;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("DB not ready after restart: "
                + (lastError == null ? "no error" : lastError.getClass().getSimpleName() + ": " + lastError.getMessage()));
    }

    /** 재기동 후 호스트 매핑 포트 새로 조회 (Docker가 새 포트 할당). */
    private static int inspectMappedPort(String containerId) {
        DockerClient client = DockerClientFactory.lazyClient();
        InspectContainerResponse insp = client.inspectContainerCmd(containerId).exec();
        Ports ports = insp.getNetworkSettings().getPorts();
        com.github.dockerjava.api.model.Ports.Binding[] bindings = ports.getBindings()
                .get(com.github.dockerjava.api.model.ExposedPort.tcp(5432));
        if (bindings == null || bindings.length == 0) {
            throw new IllegalStateException("No host port mapping for 5432 after restart");
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
