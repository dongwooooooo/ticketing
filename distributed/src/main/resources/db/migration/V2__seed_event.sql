-- 이벤트 1개 + 회차 1개 + 5구역 + 좌석 50,000 seed

INSERT INTO event (id, name, organizer) VALUES (1, 'BTS WORLD TOUR ARIRANG IN SEOUL', 'BIGHIT MUSIC');

INSERT INTO schedule (id, event_id, starts_at, sales_open_at) VALUES
  (1, 1, '2026-08-15 19:00:00', '2026-06-01 20:00:00');

INSERT INTO section (id, schedule_id, name, price) VALUES
  (1, 1, 'VIP', 250000),
  (2, 1, 'R', 180000),
  (3, 1, 'S', 130000),
  (4, 1, 'A', 90000),
  (5, 1, 'STANDING', 80000);

INSERT INTO seat (section_id, seat_no, status)
SELECT 1, s, 'AVAILABLE' FROM generate_series(1, 2000) s;

INSERT INTO seat (section_id, seat_no, status)
SELECT 2, s, 'AVAILABLE' FROM generate_series(1, 8000) s;

INSERT INTO seat (section_id, seat_no, status)
SELECT 3, s, 'AVAILABLE' FROM generate_series(1, 15000) s;

INSERT INTO seat (section_id, seat_no, status)
SELECT 4, s, 'AVAILABLE' FROM generate_series(1, 15000) s;

INSERT INTO seat (section_id, seat_no, status)
SELECT 5, s, 'AVAILABLE' FROM generate_series(1, 10000) s;

SELECT setval('event_id_seq', (SELECT max(id) FROM event));
SELECT setval('schedule_id_seq', (SELECT max(id) FROM schedule));
SELECT setval('section_id_seq', (SELECT max(id) FROM section));
