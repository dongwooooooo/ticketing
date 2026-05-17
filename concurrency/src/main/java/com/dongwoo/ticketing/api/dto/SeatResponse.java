package com.dongwoo.ticketing.api.dto;

import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.domain.SeatStatus;

public record SeatResponse(
        Long id,
        Long sectionId,
        Integer seatNo,
        SeatStatus status
) {
    public static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.getId(), seat.getSectionId(), seat.getSeatNo(), seat.getStatus());
    }
}
