-- ============================================================
-- BrainboxApi V38: teacher timetable, room bookings, house/peer/community
-- groups and schedule changes (doc 04 section 9). All teacher-owned rows carry
-- a unique client_id so the app's offline outbox can replay writes idempotently.
-- ============================================================

CREATE TABLE teacher_timetable_entries (
    id                   uuid PRIMARY KEY,
    client_id            varchar(80) NOT NULL,
    teacher_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id            uuid,
    class_id             varchar(80),
    class_name           varchar(200) NOT NULL DEFAULT '',
    subject              varchar(128) NOT NULL DEFAULT '',
    day_of_week          integer NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time           varchar(8) NOT NULL,
    end_time             varchar(8) NOT NULL,
    room_id              varchar(80),
    room_name            varchar(200),
    color_hex            varchar(16),
    house_id             varchar(80),
    community_service_id varchar(80),
    peer_circle_id       varchar(80),
    entry_type           varchar(32) NOT NULL DEFAULT 'LECTURE',
    practical_block_type varchar(32) NOT NULL DEFAULT 'NONE'
                         CHECK (practical_block_type IN ('LAB_PERIOD','FIELD_WORK','COMMUNITY_WORKSHOP','NONE')),
    created_at           timestamp with time zone NOT NULL DEFAULT now(),
    updated_at           timestamp with time zone NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_timetable_entry_client ON teacher_timetable_entries (client_id);
CREATE INDEX ix_timetable_entries_teacher ON teacher_timetable_entries (teacher_id, day_of_week, start_time);

CREATE TABLE teacher_room_bookings (
    id           uuid PRIMARY KEY,
    client_id    varchar(80) NOT NULL,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id    uuid,
    room_id      varchar(80) NOT NULL,
    room_name    varchar(200) NOT NULL,
    teacher_name varchar(160),
    start_time   timestamp with time zone NOT NULL,
    end_time     timestamp with time zone NOT NULL,
    purpose      varchar(255),
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_room_booking_client ON teacher_room_bookings (client_id);
CREATE INDEX ix_room_bookings_room ON teacher_room_bookings (room_id, start_time);
CREATE INDEX ix_room_bookings_school ON teacher_room_bookings (school_id, start_time);

CREATE TABLE teacher_house_groups (
    id           uuid PRIMARY KEY,
    client_id    varchar(80) NOT NULL,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id    uuid,
    house_id     varchar(80),
    house_name   varchar(200) NOT NULL DEFAULT '',
    house_color  varchar(16),
    class_id     varchar(80),
    member_count integer NOT NULL DEFAULT 0,
    student_ids  text,
    is_active    boolean NOT NULL DEFAULT TRUE,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_house_group_client ON teacher_house_groups (client_id);
CREATE INDEX ix_house_groups_teacher ON teacher_house_groups (teacher_id);

CREATE TABLE teacher_peer_circles (
    id           uuid PRIMARY KEY,
    client_id    varchar(80) NOT NULL,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id    uuid,
    circle_name  varchar(200) NOT NULL DEFAULT '',
    student_ids  text,
    is_active    boolean NOT NULL DEFAULT TRUE,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_peer_circle_client ON teacher_peer_circles (client_id);
CREATE INDEX ix_peer_circles_teacher ON teacher_peer_circles (teacher_id);

CREATE TABLE teacher_community_services (
    id                uuid PRIMARY KEY,
    client_id         varchar(80) NOT NULL,
    teacher_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id         uuid,
    service_name      varchar(200) NOT NULL DEFAULT '',
    students_assigned text,
    is_active         boolean NOT NULL DEFAULT TRUE,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_community_service_client ON teacher_community_services (client_id);
CREATE INDEX ix_community_services_teacher ON teacher_community_services (teacher_id);

CREATE TABLE teacher_schedule_changes (
    id            uuid PRIMARY KEY,
    client_id     varchar(80) NOT NULL,
    teacher_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id     uuid,
    teacher_name  varchar(160),
    day_label     varchar(32) NOT NULL,
    class_name    varchar(200) NOT NULL DEFAULT '',
    subject       varchar(128) NOT NULL DEFAULT '',
    start_time    varchar(8) NOT NULL,
    end_time      varchar(8) NOT NULL,
    reason        text,
    status        varchar(16) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    requested_at  timestamp with time zone NOT NULL,
    decided_at    timestamp with time zone,
    decided_by    uuid,
    decision_note text,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_schedule_change_client ON teacher_schedule_changes (client_id);
CREATE INDEX ix_schedule_changes_teacher ON teacher_schedule_changes (teacher_id, status);
CREATE INDEX ix_schedule_changes_school ON teacher_schedule_changes (school_id, status);
