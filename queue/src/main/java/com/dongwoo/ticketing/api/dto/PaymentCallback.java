package com.dongwoo.ticketing.api.dto;

import jakarta.validation.constraints.NotNull;

public record PaymentCallback(
        @NotNull Long paymentId,
        @NotNull String result   // "SUCCESS" | "FAIL"
) {
}
