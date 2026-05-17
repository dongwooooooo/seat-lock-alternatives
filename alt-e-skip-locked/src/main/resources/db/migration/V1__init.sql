-- 대안 E — SELECT FOR UPDATE SKIP LOCKED 전용 스키마.
-- version 없음, partial UNIQUE 없음. row-level lock만으로 동시성 차단.

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
-- partial UNIQUE 일부러 미적용 — SKIP LOCKED 단독 효과(또는 한계)를 측정.
