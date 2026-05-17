-- Deep stress test 스키마. stress-baseline 과 동일 구조.
-- 추가 시나리오 (deadlock, lock timeout, starvation, rollback, leak)을 다루지만 스키마는 동일.

CREATE TABLE seat (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
);

CREATE TABLE reservation (
    id BIGSERIAL PRIMARY KEY,
    seat_id BIGINT NOT NULL REFERENCES seat(id),
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservation_seat ON reservation(seat_id);

-- 좌석당 활성(HELD/PAID) reservation 1건만 허용.
CREATE UNIQUE INDEX uq_reservation_seat_active
    ON reservation (seat_id)
    WHERE status IN ('HELD', 'PAID');

-- seed: 좌석 100개 (deep test는 좌석 수가 핵심 아님, 시나리오별 동시성이 핵심)
INSERT INTO seat (id, status) SELECT generate_series(1, 100), 'AVAILABLE';
SELECT setval('seat_id_seq', 100);
