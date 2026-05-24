package com.dongwoo.ticketing.queue;

import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Stage 4 — Redis Sorted Set 기반 분산 대기열.
 *
 * 키 구성:
 *  - queue:waiting  (ZSET, score=enqueue epoch millis, member=token)
 *  - queue:admitted (SET, 통과된 토큰. 키 단위 TTL 로 만료 근사)
 *
 * In-process queue 와의 차이:
 *  - 모든 backend 인스턴스가 같은 Redis 를 본다 → state 외부화.
 *  - admitNext 는 단일 Lua atomic script — ZRANGE + ZREM + SADD 가 race-free.
 *  - 인스턴스 N 대 중 한 곳에서 admit 되면 다른 곳도 isAdmitted=true 로 본다.
 *
 * 한계:
 *  - admit 의 TTL 은 키 단위로만 가능 — 토큰별 정확 TTL 은 불가능. 60s/300s 단위 만료 근사 OK.
 *  - dispatcher 는 모든 인스턴스에서 동시 호출되어도 Lua 가 race-free 라 OK (각자 N 명씩 admit).
 *    실제 운영은 ShedLock 으로 1 인스턴스만 dispatcher 돌리는 게 폭주 방지에 유리.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "queue.impl", havingValue = "redis", matchIfMissing = true)
public class RedisWaitingQueue implements WaitingQueue {

    public static final String WAITING_KEY = "queue:waiting";
    public static final String ADMITTED_KEY = "queue:admitted";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> admitScript;
    private final long admittedTtlSeconds;

    public RedisWaitingQueue(StringRedisTemplate redis,
                             @Value("${queue.admitted-ttl-ms:300000}") long admittedTtlMs) {
        this.redis = redis;
        this.admittedTtlSeconds = Math.max(1, admittedTtlMs / 1000);
        String lua = """
            local waiting = KEYS[1]
            local admitted = KEYS[2]
            local n = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            if n <= 0 then return 0 end
            local tokens = redis.call('ZRANGE', waiting, 0, n - 1)
            if #tokens == 0 then return 0 end
            for i = 1, #tokens do
              redis.call('SADD', admitted, tokens[i])
              redis.call('ZREM', waiting, tokens[i])
            end
            redis.call('EXPIRE', admitted, ttl)
            return #tokens
            """;
        this.admitScript = new DefaultRedisScript<>(lua, Long.class);
    }

    @Override
    public String enqueue(String userId) {
        String token = UUID.randomUUID().toString();
        long score = System.currentTimeMillis();
        redis.opsForZSet().add(WAITING_KEY, token, score);
        return token;
    }

    @Override
    public long position(String token) {
        Long rank = redis.opsForZSet().rank(WAITING_KEY, token);
        if (rank != null) {
            return rank + 1L;
        }
        Boolean admitted = redis.opsForSet().isMember(ADMITTED_KEY, token);
        if (Boolean.TRUE.equals(admitted)) {
            return -1L; // ALREADY_ADMITTED
        }
        return -2L; // NOT_FOUND
    }

    @Override
    public int admitNext(int n) {
        List<String> keys = List.of(WAITING_KEY, ADMITTED_KEY);
        Long admitted = redis.execute(
                admitScript,
                keys,
                Integer.toString(n),
                Long.toString(admittedTtlSeconds));
        return admitted == null ? 0 : admitted.intValue();
    }

    @Override
    public boolean isAdmitted(String token) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(ADMITTED_KEY, token));
    }

    /** 테스트용 reset. */
    public void reset() {
        redis.delete(List.of(WAITING_KEY, ADMITTED_KEY));
    }

    public long waitingCount() {
        Long size = redis.opsForZSet().size(WAITING_KEY);
        return size == null ? 0L : size;
    }

    public long admittedCount() {
        Long size = redis.opsForSet().size(ADMITTED_KEY);
        return size == null ? 0L : size;
    }
}
