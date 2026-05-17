package com.dongwoo.ticketing.api;

import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.api.dto.PaymentRequest;
import com.dongwoo.ticketing.api.dto.PaymentResponse;
import com.dongwoo.ticketing.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> request(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @RequestHeader(value = "X-Request-Hash", required = false) String requestHash,
            @Valid @RequestBody PaymentRequest body,
            HttpServletRequest request) {
        var payment = paymentService.request(body.reservationId(), body.amount(), idempotencyKey, requestHash);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(PaymentResponse.from(payment));
    }

    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@Valid @RequestBody PaymentCallback callback) {
        paymentService.handleCallback(callback);
        return ResponseEntity.ok().build();
    }
}
