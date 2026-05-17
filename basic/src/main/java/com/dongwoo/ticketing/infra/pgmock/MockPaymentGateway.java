package com.dongwoo.ticketing.infra.pgmock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 외부 PG의 최소 동작 모사 — 결제 요청 후 일정 latency 후 callback POST 1회.
 *
 * latency 기준: 평균 120ms ±60ms (분포).
 * 출처: Stripe production median latency ~120ms
 *   (https://medium.com/@warstories/the-stripe-latency-post-mortem-every-engineer-should-read-before-launching-their-api-6514411772f8)
 * Toss 공식 latency SLA는 공개 자료 없음 → Stripe 실측값 차용.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MockPaymentGateway {

    @Value("${ticketing.pgmock.latency-mean-ms:120}")
    private int latencyMeanMs;

    @Value("${ticketing.pgmock.latency-jitter-ms:60}")
    private int latencyJitterMs;

    @Value("${ticketing.pgmock.callback-url:http://localhost:8080/payments/callback}")
    private String callbackUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Async
    public void dispatchPaymentCallback(Long paymentId) {
        sleepWithJitter();

        Map<String, Object> body = Map.of("paymentId", paymentId, "result", "SUCCESS");
        try {
            restTemplate.postForEntity(callbackUrl, body, Void.class);
        } catch (Exception e) {
            log.warn("Callback dispatch failed: paymentId={}, message={}", paymentId, e.getMessage());
        }
    }

    private void sleepWithJitter() {
        int jitter = latencyJitterMs == 0
                ? 0
                : ThreadLocalRandom.current().nextInt(-latencyJitterMs, latencyJitterMs + 1);
        int delay = Math.max(0, latencyMeanMs + jitter);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
