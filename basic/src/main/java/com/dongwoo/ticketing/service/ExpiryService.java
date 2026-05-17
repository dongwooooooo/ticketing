package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stage 1 naive 만료 스케줄러.
 *
 * 의도적 결함 (Stage 2/4에서 해결):
 *  - findByStatusAndExpiresAtBefore + markExpired는 atomic UPDATE 아님.
 *    결제 callback과 동시 진입 시 PAID 상태가 EXPIRED로 덮일 수 있다 (lost update).
 *  - 다중 인스턴스 환경에서 모든 인스턴스의 @Scheduled가 동시 실행 (Stage 4 ShedLock).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpiryService {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;

    /** 5초마다 만료된 HELD 예약 처리. */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void expireOverdueReservations() {
        List<Reservation> overdue = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.HELD, LocalDateTime.now());

        if (overdue.isEmpty()) return;
        log.info("Expiring {} reservations", overdue.size());

        for (Reservation r : overdue) {
            // Stage 1 naive: 상태 재확인 없이 EXPIRED 마킹 → 동시 결제 callback이 진행 중이면 race
            r.markExpired();

            seatRepository.findById(r.getSeatId()).ifPresent(seat -> {
                seat.release();
                seatRepository.save(seat);
            });
        }
    }
}
