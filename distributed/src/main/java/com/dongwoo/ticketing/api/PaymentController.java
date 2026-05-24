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

    /**
     * Stage 4 — outbox INSERT 만 하고 200 OK 빠르게 반환.
     * 실제 처리는 OutboxWorker 가 비동기 처리.
     */
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@Valid @RequestBody PaymentCallback callback) {
        paymentService.enqueueCallback(callback);
        return ResponseEntity.ok().build();
    }
}
