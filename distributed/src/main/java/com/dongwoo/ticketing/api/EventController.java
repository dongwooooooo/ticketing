package com.dongwoo.ticketing.api;

import com.dongwoo.ticketing.domain.Schedule;
import com.dongwoo.ticketing.domain.Section;
import com.dongwoo.ticketing.repository.ScheduleRepository;
import com.dongwoo.ticketing.repository.SectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final ScheduleRepository scheduleRepository;
    private final SectionRepository sectionRepository;

    @GetMapping("/{eventId}/schedules")
    public List<Schedule> listSchedules(@PathVariable Long eventId) {
        return scheduleRepository.findByEventId(eventId);
    }

    @GetMapping("/{eventId}/schedules/{scheduleId}/sections")
    public List<Section> listSections(@PathVariable Long eventId, @PathVariable Long scheduleId) {
        return sectionRepository.findByScheduleId(scheduleId);
    }
}
