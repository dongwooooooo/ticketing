package com.dongwoo.ticketing;

import com.dongwoo.ticketing.domain.Seat;
import com.dongwoo.ticketing.domain.SeatStatus;
import com.dongwoo.ticketing.lock.DistributedSeatLock;
import com.dongwoo.ticketing.lock.DistributedSeatLock.LockHandle;
import com.dongwoo.ticketing.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fencing token — GC pause stale holder 차단.
 *
 * 시나리오 (사용자 행동 / 부위 / 원인 / 결과):
 *  - 행동: 양쪽 사용자가 동시에 같은 좌석 R1 클릭 (B 가 A 의 stale holder 인 척)
 *  - 부위: SeatRepository.casHold + V4 ALTER (lock_token 컬럼)
 *  - 원인: A 의 fence(=1) 가 B 의 fence(=2) 보다 작아 WHERE 절 false
 *  - 결과: A 는 "seat not available" 응답, B 만 HELD 상태
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class FencingTokenTest {

    @Autowired
    DistributedSeatLock seatLock;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    EntityManager em;

    private long seatId;

    @BeforeEach
    void resetState() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        Seat seat = seatRepository.findBySectionIdAndStatus(1L, SeatStatus.AVAILABLE,
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        em.createNativeQuery("UPDATE seat SET status='AVAILABLE', lock_token=0 WHERE id = :id")
                .setParameter("id", seat.getId())
                .executeUpdate();
        this.seatId = seat.getId();
    }

    @Test
    void staleHolderBlockedByFenceCheck() {
        // 1) A 가 락 잡고 fence=1 받음
        LockHandle handleA = seatLock.acquire(seatId);
        assertThat(handleA.acquired()).isTrue();
        long fenceA = handleA.fence();

        // 2) A 의 락이 만료된 상황 시뮬레이션 — Redis DEL
        redis.delete("seat:lock:" + seatId);

        // 3) B 가 같은 좌석 락 잡고 fence=2 받음
        LockHandle handleB = seatLock.acquire(seatId);
        assertThat(handleB.acquired()).isTrue();
        long fenceB = handleB.fence();
        assertThat(fenceB).isGreaterThan(fenceA);

        // 4) B 가 먼저 casHold 성공
        int affectedB = seatRepository.casHold(seatId, fenceB);
        assertThat(affectedB).isEqualTo(1);

        // 5) A 가 깨어나 casHold(fenceA) 시도 → stale 이라 affected=0
        int affectedA = seatRepository.casHold(seatId, fenceA);
        assertThat(affectedA).isEqualTo(0);

        // 6) DB 상태 — B 의 fence 가 마지막 lock_token
        em.clear();
        Seat refreshed = seatRepository.findById(seatId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(refreshed.getLockToken()).isEqualTo(fenceB);
    }

    @Test
    void fenceMonotonicWithDb() {
        // 시작 fence 는 0
        Seat initial = seatRepository.findById(seatId).orElseThrow();
        assertThat(initial.getLockToken()).isEqualTo(0L);

        // 첫 락 — fence=1
        LockHandle h1 = seatLock.acquire(seatId);
        assertThat(seatRepository.casHold(seatId, h1.fence())).isEqualTo(1);
        em.clear();
        assertThat(seatRepository.findById(seatId).orElseThrow().getLockToken()).isEqualTo(h1.fence());

        // 이미 HELD 상태이므로 fence=2 로 시도해도 0 (status='AVAILABLE' 조건 false)
        long fence2 = seatLock.currentFence(seatId) + 1; // 추측
        assertThat(seatRepository.casHold(seatId, fence2)).isEqualTo(0);
    }
}
