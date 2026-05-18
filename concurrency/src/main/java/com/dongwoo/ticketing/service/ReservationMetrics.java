package com.dongwoo.ticketing.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage 2 Deep Dive — ReservationService 진입/DB 도달 카운터.
 *
 * 시나리오 #10 측정용. Prometheus 대신 자체 카운터로 fast path 효과를 직접 비교한다.
 *  - dbHits: SELECT FOR UPDATE 까지 도달한 횟수 (실제 DB 부하)
 *  - fastPathRejects: SoldOutCache에서 즉시 거절된 횟수
 *  - serviceCalls: reserve() 진입 총 횟수
 */
@Component
public class ReservationMetrics {

    private final AtomicLong serviceCalls = new AtomicLong();
    private final AtomicLong dbHits = new AtomicLong();
    private final AtomicLong fastPathRejects = new AtomicLong();

    public void incServiceCall() {
        serviceCalls.incrementAndGet();
    }

    public void incDbHit() {
        dbHits.incrementAndGet();
    }

    public void incFastPathReject() {
        fastPathRejects.incrementAndGet();
    }

    public long serviceCalls() {
        return serviceCalls.get();
    }

    public long dbHits() {
        return dbHits.get();
    }

    public long fastPathRejects() {
        return fastPathRejects.get();
    }

    public void reset() {
        serviceCalls.set(0);
        dbHits.set(0);
        fastPathRejects.set(0);
    }
}
