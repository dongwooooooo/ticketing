-- Stage 4: 분산 구성에 필요한 테이블/컬럼

-- (1) seat fencing token — 분산 락 stale holder 차단
-- 락을 획득할 때마다 fence 가 단조 증가. 모든 critical UPDATE 는 lock_token <= ? 검증.
ALTER TABLE seat ADD COLUMN lock_token BIGINT NOT NULL DEFAULT 0;

-- (2) outbox — 결제 callback 응답 짧게 + 비동기 처리
-- callback handler: INSERT outbox + 200 OK 반환 (수 ms)
-- worker: @Scheduled 폴링으로 SELECT FOR UPDATE SKIP LOCKED, 실제 처리는 별도 tx
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    processed_at TIMESTAMP
);
-- 폴링 인덱스 — status=PENDING 만 골라내고 created_at 순서대로
CREATE INDEX idx_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';

-- (3) ShedLock 테이블 — leader election storage (JdbcTemplateLockProvider 기본 스키마)
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
