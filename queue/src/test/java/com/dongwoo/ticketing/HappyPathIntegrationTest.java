package com.dongwoo.ticketing;

import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.domain.SeatStatus;
import com.dongwoo.ticketing.queue.InProcessWaitingQueue;
import com.dongwoo.ticketing.queue.WaitingQueue;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.repository.SeatRepository;
import com.dongwoo.ticketing.service.PaymentService;
import com.dongwoo.ticketing.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 3 — 대기열 게이트 happy path + gate denial.
 *
 * 1) HTTP 레이어 토큰 발급 → 수동 admitNext → 헤더 포함 reservation 요청 성공
 * 2) gate denial: 헤더 없이 reservation 요청 → 403
 * 3) Stage 2 단위 happy path 유지 (서비스 레이어 직접 호출, 토큰 게이트 우회)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HappyPathIntegrationTest {

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired WaitingQueue waitingQueue;
    @Autowired InProcessWaitingQueue inProcessQueue;

    @LocalServerPort int port;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void resetQueue() {
        inProcessQueue.clear();
    }

    @Test
    void stage2_reserve_then_pay_then_confirm() {
        // Stage 2 단위 happy path — 서비스 레이어 직접 호출 (gate 우회).
        Long seatId = 1L;
        String userId = "user-happy";

        var reservation = reservationService.reserve(seatId, userId);
        assertEquals(ReservationStatus.HELD, reservation.getStatus());
        assertEquals(SeatStatus.HELD, seatRepository.findById(seatId).orElseThrow().getStatus());

        var payment = paymentService.request(reservation.getId(), 250000, "idem-happy-1");
        assertNotNull(payment.getId());

        paymentService.handleCallback(new PaymentCallback(payment.getId(), "SUCCESS"));

        var reloaded = reservationRepository.findById(reservation.getId()).orElseThrow();
        assertEquals(ReservationStatus.PAID, reloaded.getStatus());
        assertEquals(SeatStatus.SOLD, seatRepository.findById(seatId).orElseThrow().getStatus());
    }

    @Test
    void stage3_with_waiting_token_passes_gate() {
        Long seatId = 2L;
        String userId = "user-stage3-pass";

        // 1) 토큰 발급
        HttpHeaders issueHeaders = new HttpHeaders();
        issueHeaders.set("X-User-Id", userId);
        var issueResp = http.exchange(
                url("/waiting/tokens"), HttpMethod.POST,
                new HttpEntity<>(issueHeaders), Map.class);
        assertEquals(HttpStatus.CREATED, issueResp.getStatusCode());
        String token = (String) issueResp.getBody().get("token");
        assertNotNull(token);
        assertEquals(1, ((Number) issueResp.getBody().get("position")).longValue());

        // 2) 수동 admitNext
        int admitted = waitingQueue.admitNext(1);
        assertEquals(1, admitted);
        assertTrue(waitingQueue.isAdmitted(token));

        // 3) 토큰 포함 reservation
        HttpHeaders reserveHeaders = new HttpHeaders();
        reserveHeaders.set("X-User-Id", userId);
        reserveHeaders.set("X-Waiting-Token", token);
        var reserveResp = http.exchange(
                url("/seats/" + seatId + "/reservations"), HttpMethod.POST,
                new HttpEntity<>(reserveHeaders), Map.class);
        assertEquals(HttpStatus.CREATED, reserveResp.getStatusCode());
        assertEquals("HELD", reserveResp.getBody().get("status"));
    }

    @Test
    void stage3_without_token_gets_403() {
        Long seatId = 3L;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "user-stage3-deny");
        // X-Waiting-Token 의도적으로 누락

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () ->
                http.exchange(url("/seats/" + seatId + "/reservations"),
                        HttpMethod.POST, new HttpEntity<>(headers), Map.class));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void stage3_with_unadmitted_token_gets_403() {
        Long seatId = 4L;
        String userId = "user-stage3-wait";

        // 토큰만 발급, admit 안 함
        String token = waitingQueue.enqueue(userId);
        assertFalse(waitingQueue.isAdmitted(token));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        headers.set("X-Waiting-Token", token);

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () ->
                http.exchange(url("/seats/" + seatId + "/reservations"),
                        HttpMethod.POST, new HttpEntity<>(headers), Map.class));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
