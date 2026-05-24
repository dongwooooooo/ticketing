package com.dongwoo.ticketing.worker;

import com.dongwoo.ticketing.api.dto.PaymentCallback;
import com.dongwoo.ticketing.outbox.OutboxEvent;
import com.dongwoo.ticketing.outbox.OutboxRepository;
import com.dongwoo.ticketing.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stage 4 — Outbox polling worker.
 *
 * 매 N ms 마다 outbox 폴링 → 처리 → DONE 마킹.
 *
 * 멀티 인스턴스 worker 안전성:
 *  - SKIP LOCKED 로 같은 row 잡지 않음. ShedLock 불필요.
 *  - 인스턴스 1 이 ID 1~50 잡으면 인스턴스 2 는 51~100 잡음 — throughput 자연 분산.
 *
 * 실패 처리:
 *  - 처리 중 exception 발생 → attempts++ 후 PENDING 으로 복귀 (다음 폴링에서 재시도)
 *  - attempts >= MAX_ATTEMPTS (10) → DEAD 마킹, 수동 개입 대상.
 *
 * 트랜잭션 경계:
 *  - 폴링 (FOR UPDATE SKIP LOCKED) 과 처리는 같은 tx — row lock 유지하며 처리.
 *  - 단 PaymentService.processCallback 은 REQUIRES_NEW 로 분리해 outbox row 와 별개 tx 로 묶음.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class OutboxWorker {

    private static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository outboxRepository;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @Value("${distributed.outbox.batch-size:50}")
    private int batchSize;

    @Value("${ticketing.instance-id:default}")
    private String instanceId;

    @PostConstruct
    void init() {
        log.info("OutboxWorker started — instance={} batchSize={}", instanceId, batchSize);
    }

    @Scheduled(fixedDelayString = "${distributed.outbox.poll-interval-ms:500}")
    @Transactional
    public void poll() {
        List<OutboxEvent> pending = outboxRepository.pollPending(batchSize);
        if (pending.isEmpty()) return;

        log.debug("instance={} polled {} outbox events", instanceId, pending.size());
        for (OutboxEvent event : pending) {
            try {
                dispatch(event);
                event.markDone();
            } catch (Exception e) {
                if (event.getAttempts() + 1 >= MAX_ATTEMPTS) {
                    log.error("Outbox event id={} DEAD after {} attempts — {}",
                            event.getId(), event.getAttempts() + 1, e.getMessage());
                    event.markDead(e.getMessage());
                } else {
                    log.warn("Outbox event id={} failed (attempt {}): {}",
                            event.getId(), event.getAttempts() + 1, e.getMessage());
                    event.markFailed(e.getMessage());
                }
            }
        }
        outboxRepository.saveAll(pending);
    }

    private void dispatch(OutboxEvent event) throws JsonProcessingException {
        if ("PAYMENT_CALLBACK".equals(event.getEventType())) {
            JsonNode node = objectMapper.readTree(event.getPayload());
            long paymentId = node.path("paymentId").asLong();
            String result = node.path("result").asText();
            processInSeparateTx(new PaymentCallback(paymentId, result));
        } else {
            throw new IllegalStateException("Unknown event type: " + event.getEventType());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void processInSeparateTx(PaymentCallback callback) {
        paymentService.processCallback(callback);
    }
}
