package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    Page<Seat> findBySectionIdAndStatus(Long sectionId, SeatStatus status, Pageable pageable);

    /**
     * Stage 2 Deep Dive 1 — Pessimistic Lock 안.
     * SELECT ... FOR UPDATE 로 row를 잠근다. 동시 100건 요청 중 1건만 통과.
     *
     * 함정 (락 안티패턴):
     *  - 트랜잭션 안에 외부 호출 들어가면 HikariCP pool 고갈 (LockCascadeReproTest)
     *  - JPA 영속성 컨텍스트에 이미 캐시된 entity가 있으면 SELECT 안 나가고 락 안 잡힐 수 있음
     *  - READ COMMITTED에서도 FOR UPDATE는 동작 (MVCC와 별개 메커니즘)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);
}
