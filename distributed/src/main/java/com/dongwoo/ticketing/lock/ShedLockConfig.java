package com.dongwoo.ticketing.lock;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Stage 4 — ShedLock JDBC provider.
 *
 * shedlock 테이블 (V4__distributed.sql 에 생성) 을 이용해 leader election.
 *
 * 동작:
 *  - @SchedulerLock(name="X") 가 발화하면 ShedLock 이 shedlock(name='X') row 의 lock_until 을 미래로 UPDATE 시도.
 *  - UPDATE 가 affected=1 이면 leader. affected=0 이면 다른 인스턴스가 잡고 있음 → skip.
 *  - lockAtMostFor 안에 인스턴스가 죽으면 lock_until 지나서 다음 발화 때 다른 인스턴스가 인계.
 */
@Configuration
@Profile("!test")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}
