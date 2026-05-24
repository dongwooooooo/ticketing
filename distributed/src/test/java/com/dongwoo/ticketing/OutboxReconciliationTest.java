package com.dongwoo.ticketing;

import com.dongwoo.ticketing.outbox.OutboxEvent;
import com.dongwoo.ticketing.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 패턴 — callback 손실 후 worker 복구.
 *
 * 시나리오:
 *  - 결제 callback 이 도착했고 PaymentController 가 outbox 에 INSERT 후 200 OK.
 *  - 그 직후 backend 인스턴스가 OOM 으로 다운.
 *  - 다른 인스턴스의 OutboxWorker 가 폴링하여 같은 row 를 SKIP LOCKED 로 잡고 처리.
 *
 * 사용자 행동 / 부위 / 원인 / 결과:
 *  - 행동: 결제 완료 후 PG → 우리 서버로 callback 전송
 *  - 부위: OutboxRepository.pollPending (SKIP LOCKED 폴링)
 *  - 원인: status='PENDING' row 가 살아있음, FOR UPDATE SKIP LOCKED 로 다른 인스턴스가 안전하게 잡음
 *  - 결과: 결제 결과가 잠시 지연되지만 결국 reservation.status='PAID' 로 반영
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OutboxReconciliationTest {

    @Autowired
    OutboxRepository outboxRepository;

    @Test
    @Transactional
    void pendingEventsCanBePolledAndMarkedDone() {
        OutboxEvent e1 = outboxRepository.save(OutboxEvent.create(
                "PAYMENT_CALLBACK", "100", "{\"paymentId\":100,\"result\":\"SUCCESS\"}"));
        OutboxEvent e2 = outboxRepository.save(OutboxEvent.create(
                "PAYMENT_CALLBACK", "101", "{\"paymentId\":101,\"result\":\"SUCCESS\"}"));
        outboxRepository.flush();

        List<OutboxEvent> polled = outboxRepository.pollPending(10);
        assertThat(polled).hasSize(2);
        assertThat(polled).extracting(OutboxEvent::getStatus).containsOnly("PENDING");

        // 처리 완료 마킹
        for (OutboxEvent e : polled) {
            e.markDone();
        }
        outboxRepository.saveAll(polled);
        outboxRepository.flush();

        assertThat(outboxRepository.countByStatus("PENDING")).isEqualTo(0);
        assertThat(outboxRepository.countByStatus("DONE")).isEqualTo(2);
    }

    @Test
    @Transactional
    void failureRetriesAndEventuallyMarksDead() {
        OutboxEvent e = outboxRepository.save(OutboxEvent.create(
                "PAYMENT_CALLBACK", "200", "{\"paymentId\":200,\"result\":\"SUCCESS\"}"));
        outboxRepository.flush();

        // 9회까지는 PENDING 으로 복귀
        for (int i = 1; i < 10; i++) {
            e.markFailed("simulated failure " + i);
        }
        assertThat(e.getAttempts()).isEqualTo(9);
        assertThat(e.getStatus()).isEqualTo("PENDING");

        // 10회째에 DEAD
        e.markDead("max attempts exceeded");
        assertThat(e.getStatus()).isEqualTo("DEAD");
    }
}
