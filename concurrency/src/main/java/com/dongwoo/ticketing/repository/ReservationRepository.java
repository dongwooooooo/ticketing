package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime cutoff);

    /**
     * Stage 2 Deep Dive 3 — atomic UPDATE 패턴.
     * HELD → PAID 전이를 한 SQL로. affected rows == 1이면 전이 성공.
     * 만료 처리(HELD→EXPIRED)나 사용자 취소(HELD→CANCELLED)가 먼저 잡았으면 affected rows == 0.
     *
     * lost update 차단:
     *   "UPDATE reservation SET status='PAID' WHERE id=? AND status='HELD'"
     *   동시 진입한 두 트랜잭션 중 하나만 affected rows == 1.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = :nextStatus WHERE r.id = :id AND r.status = :expectedStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("expectedStatus") ReservationStatus expectedStatus,
                              @Param("nextStatus") ReservationStatus nextStatus);

    /**
     * 만료 atomic UPDATE — expires_at < now() AND status='HELD' 인 row를 한 번에 EXPIRED 마킹.
     * basic의 findThen forLoop 패턴을 atomic 단일 명령으로 교체.
     * RETURNING 절은 native 또는 별도 SELECT — 여기선 ID 목록을 별도 select 후 seat 복귀.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE reservation SET status = 'EXPIRED', updated_at = now() "
                 + "WHERE status = 'HELD' AND expires_at < now()",
           nativeQuery = true)
    int expireOverdue();

    @Query(value = "SELECT seat_id FROM reservation "
                 + "WHERE status = 'EXPIRED' AND updated_at > :since",
           nativeQuery = true)
    List<Long> findSeatIdsRecentlyExpired(@Param("since") LocalDateTime since);

    /**
     * 테스트 전용 — expires_at을 과거로 강제하여 만료 조건을 트리거.
     * 실제 운영 코드에서 사용 금지.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE reservation SET expires_at = :past WHERE id = :id",
           nativeQuery = true)
    int forceExpiresAt(@Param("id") Long id, @Param("past") LocalDateTime past);
}
