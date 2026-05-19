package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    Page<Seat> findBySectionIdAndStatus(Long sectionId, SeatStatus status, Pageable pageable);

    /**
     * Stage 2 Deep Dive 1 — CAS (Compare-And-Swap) atomic UPDATE.
     *
     * AVAILABLE → HELD 전이를 단일 SQL 한 발로 처리.
     * affected rows == 1 이면 hold 성공, == 0 이면 race loss (다른 사용자가 이미 hold).
     *
     * 비관적 락 대비:
     *  - 락 보유 시간: tx 전체 → UPDATE 실행 (~1ms) 로 감소
     *  - lock-free → deadlock·lock_timeout 발화 자체가 없음
     *  - row write lock 은 단 UPDATE 실행 동안만 잡힘 (REPEATABLE READ 무관)
     *
     * seat-lock-alternatives 비교 측정 (B-1 단발 1000, C-2 데드락) 결과 채택:
     *  - p99 -67%, throughput +183%, deadlock -39%
     */
    @Modifying
    @Query(value = "UPDATE seat SET status='HELD', updated_at=now() " +
                   "WHERE id=:id AND status='AVAILABLE'",
           nativeQuery = true)
    int casHold(@Param("id") Long id);

    /**
     * HELD → AVAILABLE CAS 복구.
     * reserve() 의 후속 reservation INSERT 가 partial UNIQUE 위반으로 실패할 때
     * seat status 를 원복하기 위한 보상 동작.
     */
    @Modifying
    @Query(value = "UPDATE seat SET status='AVAILABLE', updated_at=now() " +
                   "WHERE id=:id AND status='HELD'",
           nativeQuery = true)
    int casRelease(@Param("id") Long id);

    /**
     * Pessimistic Lock — reserve() 외 경로 전용.
     *
     * reserve() 의 hot 경합은 {@link #casHold(Long)} 로 옮겼지만,
     * 아래 경로는 여전히 row entity 가 필요하므로 잠금 SELECT 를 사용한다.
     *  - PaymentService.handleCallback — SOLD / AVAILABLE 전이 (이미 HELD 상태에서 단일 owner)
     *  - ExpiryService.expireOverdueReservations — EXPIRED 후 좌석 복귀
     *  - ReservationService.cancel — 본인 취소
     *
     * 위 경로는 1좌석당 동시 진입자가 1명(callback owner / scheduler / 본인) 으로
     * 본질적으로 경합이 없어 PESSIMISTIC_WRITE 비용이 무의미하다. 단, defense-in-depth 로 유지.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);
}
