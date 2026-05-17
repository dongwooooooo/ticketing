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
 * Stage 1 PG mock. 실제 PG 호출 없이 1초 후 callback을 우리 서버로 발사한다.
 *
 * 환경변수:
 *  - ticketing.pgmock.success-rate (default 1.0) : 결제 성공률
 *  - ticketing.pgmock.duplicate-callbacks (default 0) : 동일 callback 추가 발사 횟수 (멱등성 테스트용)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MockPaymentGateway {

    @Value("${ticketing.pgmock.success-rate:1.0}")
    private double successRate;

    @Value("${ticketing.pgmock.duplicate-callbacks:0}")
    private int duplicateCallbacks;

    @Value("${ticketing.pgmock.callback-url:http://localhost:8080/payments/callback}")
    private String callbackUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Async
    public void firePaymentCallback(Long paymentId) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        boolean success = ThreadLocalRandom.current().nextDouble() < successRate;
        String result = success ? "SUCCESS" : "FAIL";

        Map<String, Object> body = Map.of("paymentId", paymentId, "result", result);

        // 1차 callback
        send(body);

        // Stage 1 의도적 중복 callback 발사 (멱등성 부재 재현용)
        for (int i = 0; i < duplicateCallbacks; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
            send(body);
        }
    }

    private void send(Map<String, Object> body) {
        try {
            restTemplate.postForEntity(callbackUrl, body, Void.class);
        } catch (Exception e) {
            log.warn("Callback send failed: {}", e.getMessage());
        }
    }
}
