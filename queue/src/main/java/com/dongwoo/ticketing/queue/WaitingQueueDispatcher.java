package com.dongwoo.ticketing.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매 100ms 마다 admitNext(N) 호출.
 *
 * 테스트에선 'test' profile로 disable — 테스트가 admitNext 수동 호출하여
 * 입장 타이밍을 결정적으로 통제하기 위함.
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
    public void tick() {
        int admitted = queue.admitNext(admitRatePerTick);
        if (admitted > 0) {
            log.debug("dispatcher admitted={}", admitted);
        }
    }
}
