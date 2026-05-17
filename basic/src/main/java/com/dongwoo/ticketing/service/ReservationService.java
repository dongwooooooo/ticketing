package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.domain.SeatStatus;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Stage 1 naive 좌석 예매.
 *
 * 의도적 결함 (Stage 2에서 해결):
 *  - 락 없음. findById 후 메모리 검사 → 동시 N건 요청 시 모두 AVAILABLE을 읽고 모두 HELD로 기록 가능.
 *  - 결과: 같은 좌석 N명에게 판매 (oversell).
 *
 * 본 서비스는 Read-Modify-Write 갭을 의도적으로 노출한다. SeatRaceReproTest로 재현.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    @Transactional
    public Reservation reserve(Long seatId, String userId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        // Stage 1 naive: 락 없이 메모리 상태 확인
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("seat not available: " + seatId);
        }

        seat.hold();
        seatRepository.save(seat);

        Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
        return reservationRepository.save(reservation);
    }

    @Transactional
    public void cancel(Long reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("reservation not found: " + reservationId));

        if (!reservation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("not owner");
        }
        if (!reservation.isHeld()) {
            throw new IllegalStateException("cannot cancel non-HELD reservation");
        }

        reservation.markCancelled();

        Seat seat = seatRepository.findById(reservation.getSeatId())
                .orElseThrow(() -> new IllegalStateException("seat not found"));
        seat.release();
        seatRepository.save(seat);
    }
}
