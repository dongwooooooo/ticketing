package com.dongwoo.ticketing.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Stage 4 — Redis ZSET 기반 대기열 메트릭.
 * RedisWaitingQueue 빈이 있을 때만 등록.
 */
@Component
@ConditionalOnBean(RedisWaitingQueue.class)
@RequiredArgsConstructor
public class WaitingQueueMetrics {

    private final RedisWaitingQueue queue;
    private final MeterRegistry registry;

    @PostConstruct
    public void register() {
        Gauge.builder("ticketing.waiting.queue.depth", queue, q -> (double) q.waitingCount())
                .description("Number of tokens currently waiting in the Redis queue")
                .register(registry);
        Gauge.builder("ticketing.waiting.admitted.size", queue, q -> (double) q.admittedCount())
                .description("Number of tokens currently admitted (TTL bounded)")
                .register(registry);
    }
}
