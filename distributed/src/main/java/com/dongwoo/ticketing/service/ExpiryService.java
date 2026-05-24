package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.lock.DistributedSeatLock;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stage 4 — 분산 환경 만료 처리.
 *
 * 변경 (Stage 3 → Stage 4):
 *  - @SchedulerLock("seat-expiry") 로 leader election: 인스턴스 N대 중 1대만 실행.
 *  - 좌석 복귀 시 분산 락의 fence 값을 사용하여 stale holder 차단.
 *  - findByIdForUpdate 대신 casRelease(seatId, fence) 단일 UPDATE 사용.
 *
 * 시나리오 — 인스턴스 1 다운:
 *  - 인스턴스 1 가 ShedLock 잡고 expire 중 OOM 으로 다운
 *  - lock_until 까지는 다른 인스턴스도 대기 (최대 30초)
 *  - lock_until 지나면 인스턴스 2 가 다음 tick 에 인계
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpiryService {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final DistributedSeatLock seatLock;

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "seat-expiry", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    @Transactional
    public void expireOverdueReservations() {
        LocalDateTime since = LocalDateTime.now().minusSeconds(10);
        int affected = reservationRepository.expireOverdue();
        if (affected == 0) return;

        log.info("Expired {} reservations atomically (leader instance)", affected);

        List<Long> seatIds = reservationRepository.findSeatIdsRecentlyExpired(since);
        for (Long seatId : seatIds) {
            long fence = seatLock.currentFence(seatId);
            int released = seatRepository.casRelease(seatId, fence);
            if (released == 0) {
                log.debug("seat {} release skipped (already reused or fence stale)", seatId);
            }
        }
    }
}
