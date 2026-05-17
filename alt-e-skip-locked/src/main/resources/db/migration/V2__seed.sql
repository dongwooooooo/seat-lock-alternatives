-- 시나리오 2(임의 가용 좌석 디스패치) 용 시드.
-- section_id=1, seat_no 1..100, 모두 AVAILABLE.
INSERT INTO seat (section_id, seat_no, status)
SELECT 1, gs, 'AVAILABLE'
  FROM generate_series(1, 100) AS gs;
