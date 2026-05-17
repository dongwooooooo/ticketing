package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.domain.SeatStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    Page<Seat> findBySectionIdAndStatus(Long sectionId, SeatStatus status, Pageable pageable);
}
