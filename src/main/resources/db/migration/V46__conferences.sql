-- ============================================================
-- BrainboxApi V46: teacher/parent conferences (doc 04 section 15,
-- docs/ongoing/api_conference_changes.md). Both sides are offline-first, so
-- every write carries a unique client_id and the lists are authoritative.
-- ============================================================

CREATE TABLE conference_slots (
    id                  uuid PRIMARY KEY,
    client_id           varchar(80) NOT NULL,
    teacher_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_name        varchar(160),
    title               varchar(200) NOT NULL,
    slot_date           timestamp with time zone NOT NULL,
    start_time          varchar(8) NOT NULL,
    end_time            varchar(8) NOT NULL,
    duration_minutes    integer NOT NULL DEFAULT 30,
    max_bookings        integer NOT NULL DEFAULT 1,
    meet_link           varchar(512),
    is_virtual          boolean NOT NULL DEFAULT TRUE,
    location            varchar(255),
    is_recurring        boolean NOT NULL DEFAULT FALSE,
    recurrence_rule     varchar(255),
    status              varchar(16) NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('OPEN','FULL','CANCELLED','COMPLETED')),
    cancellation_reason varchar(255),
    audience_target     varchar(32) NOT NULL DEFAULT 'WHOLE_SCHOOL',
    created_by_role     varchar(16) NOT NULL DEFAULT 'TEACHER',
    linked_live_class_id varchar(80),
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_conference_slot_client ON conference_slots (client_id);
CREATE INDEX ix_conference_slots_teacher ON conference_slots (teacher_id, slot_date);

CREATE TABLE conference_bookings (
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
    status           varchar(16) NOT NULL DEFAULT 'CONFIRMED'
                     CHECK (status IN ('CONFIRMED','CANCELLED','ATTENDED')),
    reminder_sent_at timestamp with time zone,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_conference_booking_client ON conference_bookings (client_id);
CREATE INDEX ix_conference_bookings_slot ON conference_bookings (slot_id, status);
CREATE INDEX ix_conference_bookings_parent ON conference_bookings (parent_id);
