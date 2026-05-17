package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime cutoff);
}
