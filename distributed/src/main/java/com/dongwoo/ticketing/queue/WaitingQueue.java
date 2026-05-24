package com.dongwoo.ticketing.queue;

/**
 * Stage 3 — 대기열 게이트.
 *
 * 채택안: in-process LinkedBlockingQueue / ConcurrentSkipListMap 기반 (queue-alternatives 레포의 queue-b).
 * Stage 4에서 Redis 기반으로 교체 예정.
 *
 * position semantics:
 *  - 1-based ordinal (대기열 1번 = 맨 앞).
 *  - ALREADY_ADMITTED: -1
 *  - NOT_FOUND: -2
 */
public interface WaitingQueue {

    /** 사용자 enqueue. 새 token 발급 후 반환. */
    String enqueue(String userId);

    /** 토큰의 현재 대기 순번. */
    long position(String token);

    /** 큐 앞에서 N명 입장 처리. 실제 입장된 인원수 반환. */
    int admitNext(int n);

    /** 토큰이 입장 허가 상태인지. */
    boolean isAdmitted(String token);
}
