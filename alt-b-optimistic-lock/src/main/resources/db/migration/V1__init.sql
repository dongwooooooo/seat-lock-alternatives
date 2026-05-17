-- 대안 B — 낙관적 락 (@Version) 전용 스키마.
-- version 컬럼 추가. partial UNIQUE는 없음 (낙관적 락 단독 효과를 측정).

CREATE TABLE seat (
    id BIGSERIAL PRIMARY KEY,
    section_id BIGINT NOT NULL,
    seat_no INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    version BIGINT NOT NULL DEFAULT 0,
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
-- partial UNIQUE 일부러 미적용 — 낙관적 락만으로 차단되는지 입증.
