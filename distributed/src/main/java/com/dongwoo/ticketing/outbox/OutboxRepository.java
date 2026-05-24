package com.dongwoo.ticketing.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Stage 4 — SKIP LOCKED 폴링.
     *
     * 멀티 인스턴스 worker 의 throughput 확장 패턴:
     *  - 인스턴스 1: ID 1,2,3 잡음 → 처리 중
     *  - 인스턴스 2: 같은 시점에 폴링 → ID 4,5,6 잡음 (1,2,3 은 SKIP)
     *
     * 트랜잭션 안에서 호출돼야 row lock 이 유지된다.
     * native query 의 FOR UPDATE SKIP LOCKED 가 PostgreSQL 9.5+ 기능.
     */
    @Query(value = "SELECT * FROM outbox WHERE status = 'PENDING' " +
            "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> pollPending(@Param("limit") int limit);

    @Modifying
    @Query(value = "UPDATE outbox SET status = 'DEAD', processed_at = now(), last_error = :err " +
            "WHERE id = :id", nativeQuery = true)
    int markDeadById(@Param("id") long id, @Param("err") String err);

    long countByStatus(String status);
}
