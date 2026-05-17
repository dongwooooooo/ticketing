package com.dongwoo.ticketing;

import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.domain.SeatStatus;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.repository.SeatRepository;
import com.dongwoo.ticketing.service.PaymentService;
import com.dongwoo.ticketing.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Happy path: 예매 → 결제 요청 → callback 성공 → 좌석 SOLD.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HappyPathIntegrationTest {

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;

    @Test
    void reserve_then_pay_then_confirm() {
        // given: V2 seed에 의해 좌석 1 (VIP) AVAILABLE
        Long seatId = 1L;
        String userId = "user-happy";

        // 1. 좌석 예매
        var reservation = reservationService.reserve(seatId, userId);
        assertEquals(ReservationStatus.HELD, reservation.getStatus());
        assertEquals(SeatStatus.HELD, seatRepository.findById(seatId).orElseThrow().getStatus());

        // 2. 결제 요청
        var payment = paymentService.request(reservation.getId(), 250000, "idem-happy-1", "hash-1");
        assertNotNull(payment.getId());

        // 3. PG callback 직접 호출 (mock 비동기 우회)
        paymentService.handleCallback(new PaymentCallback(payment.getId(), "SUCCESS"));

        // 4. 검증
        var reloaded = reservationRepository.findById(reservation.getId()).orElseThrow();
        assertEquals(ReservationStatus.PAID, reloaded.getStatus());
        assertEquals(SeatStatus.SOLD, seatRepository.findById(seatId).orElseThrow().getStatus());
    }
}
