package com.dongwoo.ticketing.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stage 4 — 분산 환경 dispatcher.
 *
 * 모든 backend 인스턴스에서 @Scheduled 가 동시 발화하면 admitNext(N) 이 매 tick 마다 N×인스턴스수
 * 만큼 통과시키는 문제가 생긴다. @SchedulerLock 으로 매 tick 1 인스턴스만 실제 admit 수행.
 *
 * lockAtMostFor: 30초 — 인스턴스 다운 시 30초 안에 다른 인스턴스가 인계.
 * lockAtLeastFor: 100ms — 너무 짧은 tick race 방지.
 *
 * 테스트에서는 'test' profile 로 disable.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueDispatcher {

    private final WaitingQueue queue;

    @Value("${queue.admit-rate-per-tick:10}")
    private int admitRatePerTick;

    @Scheduled(fixedRate = 100)
    @SchedulerLock(name = "queue-dispatcher", lockAtMostFor = "PT30S", lockAtLeastFor = "PT0.1S")
    public void tick() {
        int admitted = queue.admitNext(admitRatePerTick);
        if (admitted > 0) {
            log.debug("dispatcher admitted={}", admitted);
        }
    }
}
