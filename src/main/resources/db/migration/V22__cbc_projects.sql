-- ============================================================
-- BrainboxApi V22: CBC projects (student social showcase)
-- Contract: docs/backend_contracts/06_... §3 + the Android CbcProjectsApi/models.
-- Counter columns on cbc_projects are maintained transactionally for fast listing.
-- ============================================================

CREATE TABLE cbc_projects (
    id               uuid PRIMARY KEY,
    student_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_name     varchar(160) NOT NULL,
    school_id        uuid REFERENCES schools (id) ON DELETE SET NULL,
    school_name      varchar(200) NOT NULL DEFAULT '',
    school_logo_url  varchar(512),
    title            varchar(220) NOT NULL,
    description      text NOT NULL DEFAULT '',
    subject          varchar(128) NOT NULL DEFAULT '',
    cbc_strand       varchar(32) NOT NULL DEFAULT '',
    cbc_sub_strand   varchar(64) NOT NULL DEFAULT '',
    grade_band       varchar(32) NOT NULL DEFAULT '',
    media_urls       text,
    thumbnail_url    varchar(512),
    cover_image_url  varchar(512),
    upvotes          integer NOT NULL DEFAULT 0,
    downvotes        integer NOT NULL DEFAULT 0,
    comment_count    integer NOT NULL DEFAULT 0,
    view_count       integer NOT NULL DEFAULT 0,
    status           varchar(16) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','APPROVED','FEATURED','REMOVED')),
    rubric_scores    text,
    tags             text,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_cbc_projects_status ON cbc_projects (status, created_at);
CREATE INDEX ix_cbc_projects_student ON cbc_projects (student_id, created_at);
CREATE INDEX ix_cbc_projects_grade_subject ON cbc_projects (grade_band, subject);

CREATE TABLE cbc_project_votes (
    id         uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES cbc_projects (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    vote_type  varchar(16) NOT NULL CHECK (vote_type IN ('UPVOTE','DOWNVOTE')),
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cbc_project_vote ON cbc_project_votes (project_id, user_id);

CREATE TABLE cbc_project_comments (
    id                uuid PRIMARY KEY,
    project_id        uuid NOT NULL REFERENCES cbc_projects (id) ON DELETE CASCADE,
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name         varchar(160) NOT NULL,
    user_role         varchar(16) NOT NULL,
    content           text NOT NULL,
    parent_comment_id uuid REFERENCES cbc_project_comments (id) ON DELETE CASCADE,
    mentions          text,
    created_at        timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_cbc_comments_project ON cbc_project_comments (project_id, created_at);

CREATE TABLE cbc_project_views (
    id        uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES cbc_projects (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    viewed_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cbc_project_view ON cbc_project_views (project_id, user_id);
