package com.dongwoo.ticketing.service;

import com.dongwoo.ticketing.api.dto.SeatResponse;
import com.dongwoo.ticketing.domain.SeatStatus;
import com.dongwoo.ticketing.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatQueryService {

    private final SeatRepository seatRepository;

    public Page<SeatResponse> findSeats(Long sectionId, SeatStatus status, Pageable pageable) {
        return seatRepository
                .findBySectionIdAndStatus(sectionId, status, pageable)
                .map(SeatResponse::from);
    }
}
