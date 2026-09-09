-- ============================================================
-- BrainboxApi V9: learning hub
-- Contract: docs/backend_contracts/03_... §1-§2
-- Posts carry scope (GLOBAL/SCHOOL/SCHOOL_GRADE_CLASS) enforced server-side;
-- quiz blocks keep their key material server-side only. JSON list fields are
-- stored as text for Postgres/H2 parity.
-- ============================================================

CREATE TABLE learning_posts (
    id                uuid PRIMARY KEY,
    title             varchar(255) NOT NULL,
    subject           varchar(64)  NOT NULL,
    topic             varchar(255),
    subtopic          varchar(255),
    image_url         varchar(512),
    description       text,
    estimated_minutes integer NOT NULL DEFAULT 5,
    difficulty        integer NOT NULL DEFAULT 3 CHECK (difficulty BETWEEN 1 AND 5),
    tags              text,
    scope             varchar(32) NOT NULL DEFAULT 'GLOBAL'
                      CHECK (scope IN ('GLOBAL','SCHOOL','SCHOOL_GRADE_CLASS')),
    school_id         uuid REFERENCES schools (id),
    grade_level       varchar(64),
    teacher_id        uuid REFERENCES users (id),
    is_featured       boolean NOT NULL DEFAULT FALSE,
    is_published      boolean NOT NULL DEFAULT TRUE,
    view_count        integer NOT NULL DEFAULT 0,
    like_count        integer NOT NULL DEFAULT 0,
    created_by        uuid NOT NULL REFERENCES users (id),
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_learning_posts_scope ON learning_posts (scope, is_published);
CREATE INDEX ix_learning_posts_subject ON learning_posts (subject);

CREATE TABLE learning_content (
    id               uuid PRIMARY KEY,
    post_id          uuid NOT NULL REFERENCES learning_posts (id) ON DELETE CASCADE,
    c_type           varchar(16) NOT NULL
                     CHECK (c_type IN ('NOTES','VIDEO','QUIZ','DIAGRAM','DOCUMENT')),
    title            varchar(255),
    content          text,
    duration_minutes integer NOT NULL DEFAULT 0,
    order_index      integer NOT NULL DEFAULT 0,
    thumbnail_url    varchar(512),
    metadata         text
);

CREATE INDEX ix_learning_content_post ON learning_content (post_id, order_index);

-- One deduplicated view per (student, post) per day window (doc 03 §2.3).
CREATE TABLE learning_views (
    id        uuid PRIMARY KEY,
    post_id   uuid NOT NULL REFERENCES learning_posts (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day_key   varchar(16) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_learning_view ON learning_views (user_id, post_id, day_key);
