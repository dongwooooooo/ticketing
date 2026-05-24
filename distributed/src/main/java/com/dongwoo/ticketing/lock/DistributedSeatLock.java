package com.dongwoo.ticketing.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Stage 4 — 좌석 분산 락 + fencing token.
 *
 * 동작:
 *  - acquire(seatId): Redis 키 seat:lock:{seatId} 에 SETNX + EX TTL 로 락 시도. 성공하면 fencing token INCR.
 *  - release(seatId, holder): Lua 로 GET 후 holder 동일하면 DEL. 다른 holder 면 no-op.
 *
 * Kleppmann fencing token 원칙:
 *  - 락만으로는 충분치 않다. GC pause 시나리오:
 *    1) A 가 락 잡고 fence=5 를 받음
 *    2) A 가 stop-the-world GC (5초+) 들어가 락 TTL 만료
 *    3) B 가 같은 키로 락 잡고 fence=6 을 받음 → DB UPDATE WHERE lock_token <= 6
 *    4) A 가 깨어나서 DB UPDATE WHERE lock_token <= 5 시도 → affected=0 (DB 가 차단)
 *  - 즉 락은 best-effort 동기화, 정합성은 fencing token 으로 DB 레이어에서 강제.
 *
 * INCR 의 단조성:
 *  - Redis INCR 은 single-shard 에서 atomic. 같은 키에 대해 모든 호출이 strict total order.
 *  - 락 키와 fence 키는 분리 (seat:lock:{id} vs seat:fence:{id}) — fence 는 TTL 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedSeatLock {

    private static final String LOCK_KEY_PREFIX = "seat:lock:";
    private static final String FENCE_KEY_PREFIX = "seat:fence:";

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

    private final StringRedisTemplate redis;

    @Value("${distributed.seat-lock.ttl-seconds:5}")
    private int ttlSeconds;

    /**
     * 락 획득 시도. 실패하면 holder == null 인 LockHandle 반환.
     */
    public LockHandle acquire(long seatId) {
        String lockKey = LOCK_KEY_PREFIX + seatId;
        String holder = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(lockKey, holder, Duration.ofSeconds(ttlSeconds));
        if (!Boolean.TRUE.equals(ok)) {
            return LockHandle.failed();
        }
        // fence 는 락 획득 후에만 발급 — 락 잡지 못한 클라이언트는 fence 못 받음.
        Long fence = redis.opsForValue().increment(FENCE_KEY_PREFIX + seatId);
        if (fence == null) {
            // 이상 상태 — 즉시 락 해제.
            release(seatId, holder);
            return LockHandle.failed();
        }
        log.debug("acquired seat lock seatId={} fence={} holder={}", seatId, fence, holder);
        return new LockHandle(true, holder, fence);
    }

    /**
     * 락 해제. holder 가 다르면 no-op (이미 만료되고 다른 클라이언트가 잡은 경우).
     */
    public boolean release(long seatId, String holder) {
        if (holder == null) return false;
        Long result = redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY_PREFIX + seatId), holder);
        boolean released = result != null && result == 1L;
        if (!released) {
            log.warn("release called by non-owner — seatId={} holder={} (lock already expired or owned by other)",
                    seatId, holder);
        }
        return released;
    }

    /** 현재 락 holder. 테스트/디버그용. */
    public String peekHolder(long seatId) {
        return redis.opsForValue().get(LOCK_KEY_PREFIX + seatId);
    }

    /** 현재 fence 값 — 다음 INCR 결과를 예측할 때 사용. */
    public long currentFence(long seatId) {
        String v = redis.opsForValue().get(FENCE_KEY_PREFIX + seatId);
        return v == null ? 0L : Long.parseLong(v);
    }

    public record LockHandle(boolean acquired, String holder, Long fence) {
        public static LockHandle failed() {
            return new LockHandle(false, null, null);
        }
    }
}
