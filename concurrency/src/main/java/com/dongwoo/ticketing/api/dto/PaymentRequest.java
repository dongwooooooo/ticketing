package com.dongwoo.ticketing.api.dto;

import jakarta.validation.constraints.NotNull;

public record PaymentRequest(
        @NotNull Long reservationId,
        @NotNull Integer amount,
        @NotNull String method
) {
}
