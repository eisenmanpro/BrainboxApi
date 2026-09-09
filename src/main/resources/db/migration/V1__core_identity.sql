-- ============================================================
-- BrainboxApi V1: core identity baseline
-- Contract: docs/backend_contracts/01_AUTH_IDENTITY_AND_ACCESS.md
-- Principles: uuid PKs (app-assigned), updated_at + version for
-- offline upsert/ETag, canonical UPPERCASE enum values stored as
-- varchar with CHECK constraints. Uses standard SQL so the same
-- script runs on PostgreSQL and H2 (PostgreSQL compatibility mode).
-- ============================================================

CREATE TABLE schools (
    id          uuid PRIMARY KEY,
    name        varchar(255) NOT NULL,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0
);

CREATE TABLE users (
    id                       uuid PRIMARY KEY,
    phone_number             varchar(32),
    email                    varchar(255),
    password_hash            varchar(255) NOT NULL,
    name                     varchar(255) NOT NULL,
    role                     varchar(16)  NOT NULL
                             CHECK (role IN ('STUDENT','TEACHER','PARENT','ADMIN')),
    sub_role                 varchar(32)
                             CHECK (sub_role IN ('CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')),
    school_id                uuid REFERENCES schools (id),
    student_admission_number varchar(64),
    parent_user_id           uuid REFERENCES users (id),
    referred_by_teacher_code varchar(64),
    joined_teacher_id        uuid,
    grade_level              varchar(32),
    is_active                boolean NOT NULL DEFAULT TRUE,
    is_verified              boolean NOT NULL DEFAULT FALSE,
    created_at               timestamp with time zone NOT NULL DEFAULT now(),
    updated_at               timestamp with time zone NOT NULL DEFAULT now(),
    last_login               timestamp with time zone,
    version                  bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_users_phone  ON users (phone_number);
CREATE UNIQUE INDEX uq_users_email  ON users (email);
CREATE UNIQUE INDEX uq_users_adm_no ON users (student_admission_number);
CREATE INDEX ix_users_role          ON users (role);
CREATE INDEX ix_users_school        ON users (school_id);
CREATE INDEX ix_users_parent        ON users (parent_user_id);

-- Device sessions (doc 01 §5): <=3 student sessions per device,
-- single session per device for teachers/parents.
CREATE TABLE user_sessions (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id      varchar(128) NOT NULL,
    role           varchar(16)  NOT NULL
                   CHECK (role IN ('STUDENT','TEACHER','PARENT')),
    is_active      boolean NOT NULL DEFAULT TRUE,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    last_active_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_sessions_user_device ON user_sessions (user_id, device_id, is_active);
CREATE INDEX ix_sessions_user_active ON user_sessions (user_id, is_active);

-- Refresh tokens: stored hashed (sha-256 hex of raw token), grouped in
-- rotation families for reuse detection (doc 01 §1.5, doc 11 §1).
CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    session_id uuid REFERENCES user_sessions (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL,
    family     uuid NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked    boolean NOT NULL DEFAULT FALSE,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_refresh_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_family ON refresh_tokens (family);

-- Subscriptions (doc 01 §4): one authoritative row per user.
CREATE TABLE subscriptions (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status      varchar(16) NOT NULL
                CHECK (status IN ('NONE','ACTIVE','EXPIRED')),
    tier        varchar(16) NOT NULL
                CHECK (tier IN ('BASE','EXPLORER','PRO')),
    expiry_date timestamp with time zone,
    total_paid  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_subscription_user ON subscriptions (user_id);
