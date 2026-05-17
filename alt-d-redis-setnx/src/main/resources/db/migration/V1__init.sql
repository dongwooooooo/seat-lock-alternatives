-- 대안 D — Redis SETNX 분산 락 전용 스키마.
-- DB는 평범. version 컬럼 없음, partial UNIQUE 없음.
-- 동시성 차단은 Redis 락이 100% 담당한다 (DB는 fallback 안전망조차 두지 않는다 — 단점 입증용).

CREATE TABLE seat (
    id BIGSERIAL PRIMARY KEY,
    section_id BIGINT NOT NULL,
    seat_no INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (section_id, seat_no)
);

CREATE TABLE reservation (
    id BIGSERIAL PRIMARY KEY,
    seat_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservation_seat ON reservation(seat_id);
-- partial UNIQUE 일부러 미적용 — Redis 락 단독 효과를 측정.
