package com.dongwoo.ticketing.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WaitingQueueMetrics {

    private final InProcessWaitingQueue queue;
    private final MeterRegistry registry;

    @PostConstruct
    public void register() {
        Gauge.builder("ticketing.waiting.queue.depth", queue, InProcessWaitingQueue::waitingSize)
                .description("Number of tokens currently waiting in the queue")
                .register(registry);
        Gauge.builder("ticketing.waiting.admitted.size", queue, InProcessWaitingQueue::admittedSize)
                .description("Number of tokens currently in admitted state (TTL bounded)")
                .register(registry);
    }
}
