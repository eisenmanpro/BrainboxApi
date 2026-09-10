-- ============================================================
-- BrainboxApi V21: live classes (student surface)
-- Contract: docs/backend_contracts/05_... §4 + the Android LiveClassApi/LiveClassesApi.
-- ============================================================

CREATE TABLE live_classes (
    id               uuid PRIMARY KEY,
    teacher_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_name     varchar(160) NOT NULL,
    school_id        uuid REFERENCES schools (id) ON DELETE SET NULL,
    title            varchar(220) NOT NULL,
    subject          varchar(128) NOT NULL,
    description      text NOT NULL DEFAULT '',
    scheduled_start  timestamp with time zone NOT NULL,
    scheduled_end    timestamp with time zone NOT NULL,
    status           varchar(16) NOT NULL DEFAULT 'SCHEDULED'
                     CHECK (status IN ('SCHEDULED','LIVE','COMPLETED','CANCELLED')),
    join_url         varchar(512),
    recording_url    varchar(512),
    max_participants integer NOT NULL DEFAULT 100,
    thumbnail_url    varchar(512),
    materials        text,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_live_classes_status ON live_classes (status, scheduled_start);
CREATE INDEX ix_live_classes_teacher ON live_classes (teacher_id);

CREATE TABLE live_registrations (
    id            uuid PRIMARY KEY,
    class_id      uuid NOT NULL REFERENCES live_classes (id) ON DELETE CASCADE,
    student_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    registered_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_live_registration ON live_registrations (class_id, student_id);

CREATE TABLE live_attendance (
    id               uuid PRIMARY KEY,
    class_id         uuid NOT NULL REFERENCES live_classes (id) ON DELETE CASCADE,
    student_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status           varchar(16) NOT NULL DEFAULT 'PRESENT' CHECK (status IN ('PRESENT','ABSENT','LATE')),
    joined_at        timestamp with time zone,
    left_at          timestamp with time zone,
    duration_minutes integer NOT NULL DEFAULT 0,
    is_present       boolean NOT NULL DEFAULT TRUE,
    reason           varchar(255),
    recorded_at      timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_live_attendance ON live_attendance (class_id, student_id);

CREATE TABLE live_polls (
    id         uuid PRIMARY KEY,
    class_id   uuid NOT NULL REFERENCES live_classes (id) ON DELETE CASCADE,
    created_by uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    question   varchar(500) NOT NULL,
    options    text NOT NULL,
    is_active  boolean NOT NULL DEFAULT TRUE,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_live_polls_class ON live_polls (class_id, created_at);

CREATE TABLE live_poll_votes (
    id           uuid PRIMARY KEY,
    poll_id      uuid NOT NULL REFERENCES live_polls (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    option_index integer NOT NULL,
    voted_at     timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_live_poll_vote ON live_poll_votes (poll_id, user_id);
