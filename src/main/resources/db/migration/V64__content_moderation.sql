-- ============================================================
-- BrainboxApi V64: content moderation schema + review workflow
-- Phase 7.4a: the human-in-the-loop review tables. A review binds to one content
-- version; a per-content-version outcome aggregates approvals/rejections and the
-- weighted score; reviewer_trust is the weight-only trust ladder; content_feedback
-- is the separate teacher rating signal (not the review decision); and
-- moderation_policies stores the console-configurable policy overrides.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: no partial indexes, UUID ids and
-- per-row timestamps/version come from BaseEntity (created_at, updated_at, version).
-- Brainbox staff (ADMIN / TEACHER+ICT_ADMIN) are excluded from reviewer_trust in
-- code, not by constraint, so the staff row simply never gets written.
-- ============================================================

CREATE TABLE moderation_policies (
    id         uuid PRIMARY KEY,
    policy_key varchar(64) NOT NULL,
    value_json text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_moderation_policies_key ON moderation_policies (policy_key);

CREATE TABLE content_reviews (
    id              uuid PRIMARY KEY,
    content_type    varchar(16) NOT NULL,
    content_id      uuid NOT NULL,
    content_version integer NOT NULL DEFAULT 1,
    reviewer_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    decision        varchar(16) NOT NULL
                    CHECK (decision IN ('APPROVE','REJECT','REQUEST_CHANGES')),
    reason_tags     text,
    comment         text,
    weight          double precision NOT NULL DEFAULT 1.0,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0
);

-- One decision per reviewer per content version; a re-decision updates the row.
CREATE UNIQUE INDEX uq_content_reviews_reviewer_content
    ON content_reviews (reviewer_id, content_type, content_id, content_version);
CREATE INDEX ix_content_reviews_content
    ON content_reviews (content_type, content_id, content_version);

CREATE TABLE moderation_outcomes (
    id                 uuid PRIMARY KEY,
    content_type       varchar(16) NOT NULL,
    content_id         uuid NOT NULL,
    content_version    integer NOT NULL DEFAULT 1,
    state              varchar(16) NOT NULL DEFAULT 'UNREVIEWED'
                       CHECK (state IN ('UNREVIEWED','REVIEWED','REJECTED')),
    approvals          integer NOT NULL DEFAULT 0,
    rejections         integer NOT NULL DEFAULT 0,
    weighted_approvals double precision NOT NULL DEFAULT 0,
    quorum_required    integer NOT NULL DEFAULT 2,
    decided_at         timestamp with time zone,
    created_at         timestamp with time zone NOT NULL DEFAULT now(),
    updated_at         timestamp with time zone NOT NULL DEFAULT now(),
    version            bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_moderation_outcomes_content
    ON moderation_outcomes (content_type, content_id, content_version);

CREATE TABLE reviewer_trust (
    id            uuid PRIMARY KEY,
    teacher_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tier          integer NOT NULL DEFAULT 0,
    reviews_count integer NOT NULL DEFAULT 0,
    agreements    integer NOT NULL DEFAULT 0,
    weight        double precision NOT NULL DEFAULT 1.0,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_reviewer_trust_teacher ON reviewer_trust (teacher_id);

CREATE TABLE content_feedback (
    id             uuid PRIMARY KEY,
    content_type   varchar(16) NOT NULL,
    content_id     uuid NOT NULL,
    reviewer_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    score          integer NOT NULL CHECK (score BETWEEN 1 AND 5),
    tags           text,
    comment        text,
    prompt_version varchar(32),
    model          varchar(64),
    agent_run_id   uuid,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_content_feedback_reviewer_content
    ON content_feedback (reviewer_id, content_type, content_id);
