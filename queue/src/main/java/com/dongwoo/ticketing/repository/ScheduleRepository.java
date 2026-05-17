package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByEventId(Long eventId);
}
