package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}
