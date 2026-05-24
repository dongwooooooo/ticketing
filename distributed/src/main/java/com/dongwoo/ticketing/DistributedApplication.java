package com.dongwoo.ticketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Stage 4 — Distributed.
 *
 * Stage 3 (queue 모듈) 베이스 위에 분산 구성을 더한다:
 *  - WaitingQueue 를 in-process → Redis ZSET 으로 교체 (state externalization)
 *  - 좌석 락에 Redis SETNX + INCR fencing token 도입 (cross-JVM 정합성)
 *  - 결제 callback 핸들러를 outbox 패턴으로 전환 (재시도 안정성)
 *  - ExpiryService 에 ShedLock 적용 (leader election — 인스턴스 N대 중 1대만 실행)
 *
 * 모든 인스턴스 stateless. backend × 2 + Nginx LB 구성 검증 대상.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class DistributedApplication {

    public static void main(String[] args) {
        SpringApplication.run(DistributedApplication.class, args);
    }

    /** Jackson ObjectMapper — OutboxWorker JSON payload 파싱용. */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
