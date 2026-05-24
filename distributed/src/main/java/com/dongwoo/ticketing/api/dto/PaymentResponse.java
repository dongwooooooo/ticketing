package com.dongwoo.ticketing.api.dto;

import com.dongwoo.ticketing.domain.Payment;
import com.dongwoo.ticketing.domain.PaymentStatus;

public record PaymentResponse(
        Long id,
        Long reservationId,
        Integer amount,
        PaymentStatus status
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getReservationId(), p.getAmount(), p.getStatus());
    }
}
