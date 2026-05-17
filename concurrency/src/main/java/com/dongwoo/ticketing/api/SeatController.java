package com.dongwoo.ticketing.api;

import com.dongwoo.ticketing.api.dto.SeatResponse;
import com.dongwoo.ticketing.domain.SeatStatus;
import com.dongwoo.ticketing.service.SeatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sections")
@RequiredArgsConstructor
public class SeatController {

    private final SeatQueryService seatQueryService;

    @GetMapping("/{sectionId}/seats")
    public Page<SeatResponse> listSeats(
            @PathVariable Long sectionId,
            @RequestParam(defaultValue = "AVAILABLE") SeatStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return seatQueryService.findSeats(sectionId, status, PageRequest.of(page, size));
    }
}
