package com.dongwoo.ticketing.repository;

import com.dongwoo.ticketing.domain.Section;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SectionRepository extends JpaRepository<Section, Long> {
    List<Section> findByScheduleId(Long scheduleId);
}
