package com.dongwoo.ticketing;

import com.dongwoo.ticketing.queue.RedisWaitingQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis ZSET 대기열 — 분산 환경 정합성.
 *
 * 시나리오:
 *  - BTS 콘서트 매표 11:00:00. 100명이 동시에 enqueue.
 *  - Redis ZSET 에 100개 토큰 score=epoch ms 로 정렬됨.
 *  - admitNext(10) 호출 → 가장 빠른 10명만 admitted SET 에 들어감.
 *  - 다른 backend 인스턴스도 같은 Redis 를 보므로 isAdmitted=true 동일하게 본다 (state 외부화).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DistributedQueueTest {

    @Autowired
    RedisWaitingQueue queue;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void reset() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void enqueueAndAdmitNextRespectsFifo() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tokens.add(queue.enqueue("user-" + i));
        }
        assertThat(queue.waitingCount()).isEqualTo(30);

        int admitted = queue.admitNext(10);
        assertThat(admitted).isEqualTo(10);
        assertThat(queue.admittedCount()).isEqualTo(10);
        assertThat(queue.waitingCount()).isEqualTo(20);

        for (int i = 0; i < 10; i++) {
            assertThat(queue.isAdmitted(tokens.get(i))).isTrue();
        }
        for (int i = 10; i < 30; i++) {
            assertThat(queue.isAdmitted(tokens.get(i))).isFalse();
        }
    }

    @Test
    void concurrentAdmitNextDoesNotDoubleAdmit() throws Exception {
        for (int i = 0; i < 100; i++) {
            queue.enqueue("u" + i);
        }
        int instances = 2;
        int admitPerCall = 50;
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger totalAdmitted = new AtomicInteger();

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                int a = queue.admitNext(admitPerCall);
                totalAdmitted.addAndGet(a);
                return a;
            }));
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(totalAdmitted.get()).isEqualTo(100);
        assertThat(queue.waitingCount()).isEqualTo(0);
        assertThat(queue.admittedCount()).isEqualTo(100);
    }

    @Test
    void positionReflectsRank() {
        String t1 = queue.enqueue("a");
        String t2 = queue.enqueue("b");
        String t3 = queue.enqueue("c");

        assertThat(queue.position(t1)).isEqualTo(1L);
        assertThat(queue.position(t2)).isEqualTo(2L);
        assertThat(queue.position(t3)).isEqualTo(3L);

        queue.admitNext(1);
        assertThat(queue.position(t1)).isEqualTo(-1L);
        assertThat(queue.position(t2)).isEqualTo(1L);
    }

    @Test
    void crossInstanceStateSharing() {
        String token = queue.enqueue("shared-user");
        RedisWaitingQueue otherInstance = new RedisWaitingQueue(redis, 300_000L);
        assertThat(otherInstance.position(token)).isEqualTo(1L);
        otherInstance.admitNext(1);
        assertThat(queue.isAdmitted(token)).isTrue();
    }

    @Test
    void uniqueTokensUnderHighEnqueueLoad() throws Exception {
        int threads = 50;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> all = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            int tid = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        all.add(queue.enqueue("u-" + tid + "-" + j));
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(all).hasSize(threads * perThread);
        assertThat(queue.waitingCount()).isEqualTo(threads * perThread);
    }
}
