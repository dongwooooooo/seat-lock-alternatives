-- 대안 F — 단일 작성자 큐 전용 스키마.
-- 락도 UNIQUE도 partial index도 없음. 정확성은 in-process worker 1개로만 보장됨.

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
-- 동시성 보호 일부러 비워둠. race가 차단되는 이유는 worker 직렬화 때문임을 입증.
