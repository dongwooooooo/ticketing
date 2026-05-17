package com.dongwoo.ticketing.queue;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process WaitingQueue 구현.
 *
 * 자료구조:
 *  - waiting: ConcurrentSkipListMap<Long, String> — seq → token. firstKey()/pollFirstEntry() O(log n).
 *  - tokenToSeq: token → seq 역방향 lookup (O(1) position 조회 / remove 위치 파악).
 *  - admitted: token → admittedAt epochMs (TTL 만료 대상).
 *
 * 락 전략:
 *  - 모든 구조는 lock-free concurrent collection. 단일 토큰의 enqueue→admit→isAdmitted 전이는
 *    happens-before 가 자료구조 자체에 의해 보장됨.
 *  - admitNext 의 pollFirstEntry / put(admitted) 사이 짧은 race 가능 — 같은 토큰이 동시에 2번
 *    poll 되는 일은 없으므로 정합성 위협 없음 (poll 자체가 atomic).
 *
 * 한계:
 *  - 단일 JVM. 분산 환경에서는 Stage 4에서 Redis로 교체.
 *  - 메모리에 모든 대기 인원 보관 — 1M 대기자 가정 시 token(36B) + seq(8B) ≈ 50MB. 허용.
 */
@Component
@Slf4j
public class InProcessWaitingQueue implements WaitingQueue {

    private final ConcurrentSkipListMap<Long, String> waiting = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<String, Long> tokenToSeq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> admitted = new ConcurrentHashMap<>();
    private final AtomicLong seqGen = new AtomicLong(0);

    private final long admittedTtlMs;

    public InProcessWaitingQueue(@Value("${queue.admitted-ttl-ms:300000}") long admittedTtlMs) {
        this.admittedTtlMs = admittedTtlMs;
    }

    @Override
    public String enqueue(String userId) {
        // userId 는 향후 audit/abuse 차단용 — 현재는 토큰 발급에 사용 안 함.
        String token = UUID.randomUUID().toString();
        long seq = seqGen.incrementAndGet();
        waiting.put(seq, token);
        tokenToSeq.put(token, seq);
        return token;
    }

    @Override
    public long position(String token) {
        if (admitted.containsKey(token)) {
            return -1;
        }
        Long seq = tokenToSeq.get(token);
        if (seq == null) {
            return -2;
        }
        // 1-based: 앞에 있는 인원수 + 1.
        // headMap(seq) 은 O(log n) — count 는 size() O(n). 대규모면 O(1) approximate 가 낫지만
        // 현재 구현은 정확도 우선. 1M 대기자 가정 시 호출당 ~ms 수준.
        return waiting.headMap(seq, true).size();
    }

    @Override
    public int admitNext(int n) {
        int admittedCount = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            Map.Entry<Long, String> head = waiting.pollFirstEntry();
            if (head == null) break;
            String token = head.getValue();
            tokenToSeq.remove(token);
            admitted.put(token, now);
            admittedCount++;
        }
        if (admittedCount > 0) {
            log.debug("admitted {} tokens, remaining queue={}", admittedCount, waiting.size());
        }
        return admittedCount;
    }

    @Override
    public boolean isAdmitted(String token) {
        Long admittedAt = admitted.get(token);
        if (admittedAt == null) return false;
        if (System.currentTimeMillis() - admittedAt > admittedTtlMs) {
            admitted.remove(token, admittedAt);
            return false;
        }
        return true;
    }

    /**
     * TTL 만료 admitted 청소. 매 1초.
     * remove(token, admittedAt) 의 CAS 의미로 갱신된 토큰을 잘못 지우지 않음.
     */
    @Scheduled(fixedRate = 1000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        admitted.forEach((token, ts) -> {
            if (now - ts > admittedTtlMs) {
                admitted.remove(token, ts);
            }
        });
    }

    /** 테스트용 reset. 운영 코드에선 사용 금지. */
    public void clear() {
        waiting.clear();
        tokenToSeq.clear();
        admitted.clear();
        seqGen.set(0);
    }

    @PreDestroy
    public void shutdown() {
        log.info("WaitingQueue shutdown — pending={}, admitted={}", waiting.size(), admitted.size());
    }
}
