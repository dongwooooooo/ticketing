package com.dongwoo.ticketing.api.dto;

import com.dongwoo.ticketing.domain.Reservation;
import com.dongwoo.ticketing.domain.ReservationStatus;

import java.time.LocalDateTime;

public record ReservationResponse(
        Long id,
        Long seatId,
        String userId,
        ReservationStatus status,
        LocalDateTime expiresAt
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(r.getId(), r.getSeatId(), r.getUserId(), r.getStatus(), r.getExpiresAt());
    }
}
