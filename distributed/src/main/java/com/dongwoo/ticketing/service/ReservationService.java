package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.lock.DistributedSeatLock;
import com.dongwoo.ticketing.lock.DistributedSeatLock.LockHandle;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Stage 4 — 분산 락 + fencing token 기반 reservation.
 *
 * 차이 (Stage 3 vs Stage 4):
 *  - Stage 3: SELECT ... FOR UPDATE (DB pessimistic lock).
 *  - Stage 4:
 *    1) Redis SETNX 로 best-effort 분산 락 → 동시 진입 자체 차단 (수많은 동시 요청을 Redis 가 흡수)
 *    2) INCR 로 fence token 발급 → 단조 증가 보장
 *    3) DB 측은 CAS UPDATE WHERE lock_token < :fence 로 stale holder 차단
 *
 * GC pause 시나리오 안전성:
 *  - A 가 락 잡고 fence=5 받음 → GC pause 10초 → 락 TTL(5초) 만료
 *  - B 가 락 잡고 fence=6 받음 → casHold(fence=6) 성공, lock_token=6
 *  - A 가 깨어나 casHold(fence=5) 시도 → 5 > 6 == false 로 affected=0 ⇒ 안전
 *
 * 함정:
 *  - 트랜잭션 안에 Redis 호출 들어가면 DB 락 보유시간 늘어남 → Redis 락 먼저 잡고 진입.
 *  - Redis 호출 중 RTT 도 critical section 시간에 더해짐 — 단일 redis op (~1ms) 정도라 허용.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final DistributedSeatLock seatLock;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    public Reservation reserve(Long seatId, String userId) {
        // 1) 분산 락 시도 — Redis 가 우선 동시 진입 차단
        LockHandle handle = seatLock.acquire(seatId);
        if (!handle.acquired()) {
            throw new SeatNotAvailableException("seat " + seatId + " is being processed by another request");
        }
        try {
            return doReserve(seatId, userId, handle.fence());
        } finally {
            seatLock.release(seatId, handle.holder());
        }
    }

    /**
     * Critical section — 짧게 유지. 락 잡혀 있는 동안만 DB 작업.
     */
    @Transactional
    protected Reservation doReserve(Long seatId, String userId, long fence) {
        // 2) CAS UPDATE — fencing 검증으로 stale holder 차단
        int affected = seatRepository.casHold(seatId, fence);
        if (affected == 0) {
            throw new SeatNotAvailableException(
                    "seat " + seatId + " not available (stale fence or already held). fence=" + fence);
        }
        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            throw new SeatNotAvailableException("seat " + seatId + " concurrent reservation rejected");
        }
    }

    @Transactional
    public void cancel(Long reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("reservation not found: " + reservationId));

        if (!reservation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("not owner");
        }

        int affected = reservationRepository.updateStatusIfCurrent(
                reservationId, ReservationStatus.HELD, ReservationStatus.CANCELLED);
        if (affected == 0) {
            throw new IllegalStateException("reservation no longer HELD (already paid/expired/cancelled)");
        }

        // 좌석 복귀 — 현재 lock_token 그대로 사용 (이미 가장 큰 fence)
        long currentFence = seatLock.currentFence(reservation.getSeatId());
        seatRepository.casRelease(reservation.getSeatId(), currentFence);
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
