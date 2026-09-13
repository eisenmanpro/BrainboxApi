-- ============================================================
-- BrainboxApi V51: conference booking approval gate
-- docs/ongoing/api_conference_changes.md section 7.
-- Bookings start PENDING and wait for the slot owner; PENDING soft-holds one
-- seat only when maxBookings == 1. A stale PENDING becomes EXPIRED.
-- The table is recreated because the old status CHECK (CONFIRMED/CANCELLED/
-- ATTENDED) is inline and not portable to alter.
-- ============================================================

CREATE TABLE conference_bookings_new (
    id               uuid PRIMARY KEY,
    client_id        varchar(80) NOT NULL,
    slot_id          uuid NOT NULL REFERENCES conference_slots (id) ON DELETE CASCADE,
    parent_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    child_id         uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_name     varchar(160),
    booking_date     timestamp with time zone NOT NULL,
    booking_time     varchar(8),
    meet_link        varchar(512),
    notes            text,
    status           varchar(16) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','ATTENDED','EXPIRED')),
    requested_at     timestamp with time zone NOT NULL DEFAULT now(),
    confirmed_at     timestamp with time zone,
    confirmed_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    reminder_sent_at timestamp with time zone,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

INSERT INTO conference_bookings_new
    (id, client_id, slot_id, parent_id, child_id, teacher_name, booking_date, booking_time,
     meet_link, notes, status, requested_at, confirmed_at, confirmed_by, reminder_sent_at,
     created_at, updated_at, version)
SELECT id, client_id, slot_id, parent_id, child_id, teacher_name, booking_date, booking_time,
       meet_link, notes, status, created_at, created_at, NULL, reminder_sent_at,
       created_at, updated_at, version
FROM conference_bookings;

DROP TABLE conference_bookings;
ALTER TABLE conference_bookings_new RENAME TO conference_bookings;

CREATE UNIQUE INDEX uq_conference_booking_client ON conference_bookings (client_id);
CREATE INDEX ix_conference_bookings_slot ON conference_bookings (slot_id, status);
CREATE INDEX ix_conference_bookings_parent ON conference_bookings (parent_id);
CREATE INDEX ix_conference_bookings_pending ON conference_bookings (status, requested_at);
