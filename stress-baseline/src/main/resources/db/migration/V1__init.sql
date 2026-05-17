-- Stage 2 baseline stress test 용 스키마.
-- 측정 대상이 풀/대기/처리량이므로 부수 컬럼 제거하고 id+status만 유지.

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

-- 핵심: 좌석당 활성(HELD/PAID) reservation 1건만 허용.
-- 비관적 락이 실패해도 partial UNIQUE가 마지막 그물.
CREATE UNIQUE INDEX uq_reservation_seat_active
    ON reservation (seat_id)
    WHERE status IN ('HELD', 'PAID');

-- seed: 좌석 1000개
INSERT INTO seat (id, status) SELECT generate_series(1, 1000), 'AVAILABLE';
SELECT setval('seat_id_seq', 1000);
