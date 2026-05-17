-- 대안 A — partial UNIQUE 단독.
-- V1에서 곧장 partial UNIQUE index 생성. 락(FOR UPDATE) 없음.

CREATE TABLE event (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    organizer VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE schedule (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES event(id),
    starts_at TIMESTAMP NOT NULL,
    sales_open_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_schedule_event ON schedule(event_id);

CREATE TABLE section (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES schedule(id),
    name VARCHAR(50) NOT NULL,
    price INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (schedule_id, name)
);
CREATE INDEX idx_section_schedule ON section(schedule_id);

CREATE TABLE seat (
    id BIGSERIAL PRIMARY KEY,
    section_id BIGINT NOT NULL REFERENCES section(id),
    seat_no INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (section_id, seat_no)
);
CREATE INDEX idx_seat_section_status ON seat(section_id, status);

CREATE TABLE reservation (
    id BIGSERIAL PRIMARY KEY,
    seat_id BIGINT NOT NULL REFERENCES seat(id),
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservation_expires_held ON reservation(expires_at) WHERE status = 'HELD';
CREATE INDEX idx_reservation_seat ON reservation(seat_id);

-- 핵심: 좌석당 활성(HELD/PAID) reservation 1건만 허용.
-- 락이 없어도 DB 레벨에서 race를 차단. 비용: 99건의 트랜잭션이 모두 INSERT까지 도달.
CREATE UNIQUE INDEX uq_reservation_seat_active
    ON reservation (seat_id)
    WHERE status IN ('HELD', 'PAID');

-- seed data: seat id=100 까지 존재하도록 보장
INSERT INTO event (id, name, organizer) VALUES (1, 'BENCH EVENT', 'BENCH');
INSERT INTO schedule (id, event_id, starts_at, sales_open_at) VALUES
  (1, 1, '2026-08-15 19:00:00', '2026-06-01 20:00:00');
INSERT INTO section (id, schedule_id, name, price) VALUES
  (1, 1, 'VIP', 100000);
INSERT INTO seat (section_id, seat_no, status)
SELECT 1, s, 'AVAILABLE' FROM generate_series(1, 200) s;

SELECT setval('event_id_seq', (SELECT max(id) FROM event));
SELECT setval('schedule_id_seq', (SELECT max(id) FROM schedule));
SELECT setval('section_id_seq', (SELECT max(id) FROM section));
