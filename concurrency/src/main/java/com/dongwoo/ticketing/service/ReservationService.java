package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.repository.ReservationRepository;
import com.dongwoo.ticketing.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Stage 2 Deep Dive 1 — 좌석 동시 선점 차단.
 *
 * 채택 안: CAS (Compare-And-Swap) atomic UPDATE.
 *  - UPDATE seat SET status='HELD' WHERE id=? AND status='AVAILABLE'
 *  - affected rows == 1 → hold 성공, == 0 → race loss (다른 사용자가 이미 hold)
 *  - row write lock 보유 시간 = UPDATE 실행 시간 (~1ms). 비관적 락 대비 락 보유 시간 크게 감소.
 *
 * 비관적 락에서 전환한 근거 (seat-lock-alternatives 비교 측정):
 *  - B-1 (단발 1000) p99 -67%, throughput +183%
 *  - C-2 (데드락 시나리오) deadlock 발생 -39%
 *  - lock-free → deadlock·lock_timeout 발화 자체가 없음
 *
 * 2-line defense (변경 없이 유지):
 *  - 1차: 위 atomic UPDATE
 *  - 2차: V3 partial UNIQUE index — `uq_reservation_seat_active (seat_id) WHERE status IN ('HELD','PAID')`
 *    reservation INSERT 시 race 가 좁은 window 통과해도 DB 레벨에서 1건 보장.
 *
 * 함정 / 주의:
 *  - CAS UPDATE 는 JPA flush cycle 우회 (native SQL) — 영속성 컨텍스트의 Seat entity 와 분리됨.
 *    reserve() 가 Seat 을 반환하지 않으므로 stale entity 노출 위험 없음.
 *  - reservation INSERT 의 UNIQUE 위반 fallback 시 seat status 를 AVAILABLE 로 보상 (casRelease).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final SoldOutCache soldOutCache;
    private final ReservationMetrics metrics;

    @Value("${ticketing.fast-path.enabled:false}")
    private boolean fastPathEnabled;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    @Transactional
    public Reservation reserve(Long seatId, String userId) {
        metrics.incServiceCall();

        // fast path — application-level 매진 차단. 봇 트래픽 흡수용.
        if (fastPathEnabled && soldOutCache.isSoldOut(seatId)) {
            metrics.incFastPathReject();
            throw new SeatNotAvailableException("seat " + seatId + " sold out (fast path)");
        }

        metrics.incDbHit();

        // 1차 CAS: atomic UPDATE AVAILABLE → HELD. lock-free.
        int updated = seatRepository.casHold(seatId);
        if (updated == 0) {
            // race loss — 좌석이 AVAILABLE 이 아님 (이미 HELD/SOLD 이거나 존재하지 않음)
            soldOutCache.markSoldOut(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " not AVAILABLE (CAS miss)");
        }
        soldOutCache.markSoldOut(seatId);

        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            // 2차 partial UNIQUE 위반 — 최후 방어선. seat status 복구 후 거절.
            seatRepository.casRelease(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " concurrent reservation rejected");
        }
    }

    /**
     * 사용자 자가 취소 — atomic UPDATE WHERE status='HELD' 패턴 적용.
     * callback이 동시 진입하면 affected rows로 판별.
     */
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

        Seat seat = seatRepository.findByIdForUpdate(reservation.getSeatId())
                .orElseThrow(() -> new IllegalStateException("seat not found"));
        seat.release();
        seatRepository.save(seat);
        soldOutCache.release(seat.getId());
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
