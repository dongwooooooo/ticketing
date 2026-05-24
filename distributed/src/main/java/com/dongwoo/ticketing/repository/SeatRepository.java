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

    /** 호환용 (Stage 3 코드 재사용 경로). distributed 에선 CAS UPDATE 우선. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);

    /**
     * Stage 4 — CAS hold with fencing token.
     *
     * 의미:
     *  - 좌석이 AVAILABLE 이고 fencing token 이 단조 증가 조건 만족할 때만 HELD 로 전이.
     *  - JPA pessimistic lock 없이 단일 UPDATE 로 race 차단 (affected=1 ↔ 본인이 점유).
     *  - 동시에 들어온 N 개 요청 중 1 개만 affected=1. 나머지는 affected=0.
     *  - stale holder 차단: 락 만료된 holder 가 늦게 도착해도 fence 가 현재 lock_token 이하이면 affected=0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE seat SET status = 'HELD', lock_token = :fence, updated_at = now() " +
            "WHERE id = :id AND status = 'AVAILABLE' AND :fence > lock_token", nativeQuery = true)
    int casHold(@Param("id") long id, @Param("fence") long fence);

    /**
     * Release 도 fencing 검증.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE seat SET status = 'AVAILABLE', updated_at = now() " +
            "WHERE id = :id AND status = 'HELD' AND :fence >= lock_token", nativeQuery = true)
    int casRelease(@Param("id") long id, @Param("fence") long fence);

    /** Confirm (결제 완료) — fencing 검증. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE seat SET status = 'SOLD', updated_at = now() " +
            "WHERE id = :id AND status = 'HELD' AND :fence >= lock_token", nativeQuery = true)
    int casConfirm(@Param("id") long id, @Param("fence") long fence);
}
