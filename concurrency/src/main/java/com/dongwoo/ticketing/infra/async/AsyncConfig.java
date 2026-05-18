package com.dongwoo.ticketing.infra.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * SCN-7 — 결제 콜백 폭주 격리용 executor.
 *
 * 기본 상태 (profile 미활성):
 *  - {@code @EnableAsync} 만 켜져 있고 별도 executor bean 없음.
 *  - Spring이 fallback으로 {@link org.springframework.core.task.SimpleAsyncTaskExecutor} 사용.
 *  - 호출당 신규 thread 생성 → 1000건 burst 시 thread 1000개 + heap 폭증.
 *
 * {@code async-pool} profile 활성 시:
 *  - 본 bean이 등록되어 Spring {@code @Async}가 이 executor를 사용.
 *  - core=20, max=50, queue=500 → thread 50개 이하로 고정, queue가 burst 흡수.
 *
 * 격리 이유:
 *  - 기본 빌드/테스트는 profile 비활성으로 영향 없음 (기존 동작 유지).
 *  - 본 시나리오 테스트만 명시적으로 두 모드를 비교한다.
 */
@Configuration
@Profile("async-pool")
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(20);
        exec.setMaxPoolSize(50);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("async-pool-");
        exec.initialize();
        return exec;
    }
}
