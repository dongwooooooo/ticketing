package com.dongwoo.ticketing.service;

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
 * Stage 2 Deep Dive 3 — 만료 처리 atomic UPDATE.
 *
 * basic의 findThen-forLoop 패턴을 단일 atomic UPDATE로 교체:
 *   UPDATE reservation SET status='EXPIRED' WHERE status='HELD' AND expires_at < now()
 *
 * 결제 callback이 같은 reservation을 동시 처리해도 row lock + 조건 평가로
 * 한 트랜잭션만 affected rows == 1을 받는다. lost update 차단.
 *
 * 다중 인스턴스 환경의 중복 실행은 Stage 4 (distributed) 모듈에서 ShedLock으로 해결.
 * 본 Stage는 단일 인스턴스 가정.
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
        LocalDateTime since = LocalDateTime.now().minusSeconds(10);
        int affected = reservationRepository.expireOverdue();
        if (affected == 0) return;

        log.info("Expired {} reservations atomically", affected);

        // EXPIRED로 막 바뀐 row의 seat_id 조회 → 좌석 복귀
        List<Long> seatIds = reservationRepository.findSeatIdsRecentlyExpired(since);
        for (Long seatId : seatIds) {
            seatRepository.findByIdForUpdate(seatId).ifPresent(seat -> {
                seat.release();
                seatRepository.save(seat);
            });
        }
    }
}
