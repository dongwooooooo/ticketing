package com.dongwoo.ticketing.api;

import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.api.dto.PaymentRequest;
import com.dongwoo.ticketing.api.dto.PaymentResponse;
import com.dongwoo.ticketing.service.PaymentService;
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
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest body) {
        var payment = paymentService.request(body.reservationId(), body.amount(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(PaymentResponse.from(payment));
    }

    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@Valid @RequestBody PaymentCallback callback) {
        paymentService.handleCallback(callback);
        return ResponseEntity.ok().build();
    }
}
