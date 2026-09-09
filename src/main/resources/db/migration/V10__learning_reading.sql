-- ============================================================
-- BrainboxApi V10: reading materials, reading progress/sessions,
-- learning progress (doc 03 §3-§4)
-- ============================================================

CREATE TABLE readable_files (
    id            uuid PRIMARY KEY,
    title         varchar(255) NOT NULL,
    subject       varchar(128) NOT NULL,
    category      varchar(128),
    file_url      varchar(512) NOT NULL,
    file_type     varchar(16) NOT NULL
                  CHECK (file_type IN ('PDF','DOCX','TXT','IMAGE')),
    page_count    integer NOT NULL DEFAULT 0,
    size_bytes    bigint NOT NULL DEFAULT 0,
    file_version  integer NOT NULL DEFAULT 1,
    scope         varchar(32) NOT NULL DEFAULT 'GLOBAL'
                  CHECK (scope IN ('GLOBAL','SCHOOL','SCHOOL_GRADE_CLASS')),
    school_id     uuid REFERENCES schools (id),
    grade_level   varchar(64),
    is_active     boolean NOT NULL DEFAULT TRUE,
    created_by    uuid NOT NULL REFERENCES users (id),
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_readable_scope ON readable_files (scope, is_active);

CREATE TABLE learning_reading_progress (
    id            uuid PRIMARY KEY,
    file_id       uuid NOT NULL REFERENCES readable_files (id) ON DELETE CASCADE,
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    current_page  integer NOT NULL DEFAULT 0,
    total_pages   integer NOT NULL DEFAULT 0,
    last_read_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_reading_progress ON learning_reading_progress (user_id, file_id);

CREATE TABLE learning_reading_sessions (
    id          uuid PRIMARY KEY,
    file_id     uuid NOT NULL REFERENCES readable_files (id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    start_time  timestamp with time zone NOT NULL,
    end_time    timestamp with time zone NOT NULL,
    pages_read  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_reading_sessions_file ON learning_reading_sessions (file_id, user_id);

CREATE TABLE learning_progress (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id        uuid NOT NULL REFERENCES learning_posts (id) ON DELETE CASCADE,
    quiz_score     integer NOT NULL DEFAULT -1,
    last_viewed_at timestamp with time zone NOT NULL DEFAULT now(),
    completed      boolean NOT NULL DEFAULT FALSE,
    answers_json   text,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_learning_progress ON learning_progress (user_id, post_id);
