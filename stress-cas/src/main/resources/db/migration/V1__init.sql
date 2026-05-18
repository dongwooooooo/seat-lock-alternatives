-- CAS (Compare-And-Swap) 채택안 스키마. stress-baseline 과 동일 구조.
-- 1차 방어: atomic UPDATE seat SET status='HELD' WHERE id=? AND status='AVAILABLE'
-- 2차 방어 (최후의 그물): partial UNIQUE on reservation(seat_id) WHERE status IN ('HELD','PAID')

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

-- 좌석당 활성(HELD/PAID) reservation 1건만 허용. CAS 가 실패해도 최후 방어선.
CREATE UNIQUE INDEX uq_reservation_seat_active
    ON reservation (seat_id)
    WHERE status IN ('HELD', 'PAID');

-- seed: 좌석 1000개
INSERT INTO seat (id, status) SELECT generate_series(1, 1000), 'AVAILABLE';
SELECT setval('seat_id_seq', 1000);
