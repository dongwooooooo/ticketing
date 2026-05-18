package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.ReservationStatus;
import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.domain.SeatStatus;
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
 * 채택 안: B (DB Pessimistic Lock)
 *  - SELECT ... FOR UPDATE 로 좌석 row를 잠근다.
 *  - 동시 100건 요청 중 1건만 락 획득, 나머지는 대기 후 status=HELD 보고 실패.
 *
 * 함정 (락 안티패턴) — LockCascadeReproTest에서 의도적 재현:
 *  - 트랜잭션 안에 외부 호출 절대 금지 (HikariCP pool 고갈)
 *  - 락 보유 시간 최소화 (READ_COMMITTED + FOR UPDATE는 MVCC와 별개 메커니즘이라 그 자체로 cascade)
 *  - 좌석 단위 락이라 hot 좌석에만 직렬화 영향 (sharding by seat_id)
 *
 * 추가 차단선 — V3 마이그레이션의 partial UNIQUE index:
 *   CREATE UNIQUE INDEX uq_reservation_seat_active ON reservation (seat_id) WHERE status IN ('HELD','PAID')
 *   락 우회 케이스(JPA flush 타이밍 함정 등)에서도 DB 레벨에서 1건 보장.
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
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            // DB 진입 후 발견 — fast path가 빠뜨린 케이스(이제 막 매진 전이)도 마킹
            soldOutCache.markSoldOut(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " status=" + seat.getStatus());
        }

        seat.hold();
        seatRepository.save(seat);
        soldOutCache.markSoldOut(seatId);

        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            // partial UNIQUE index 위반 — 락 우회 케이스 fallback
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
