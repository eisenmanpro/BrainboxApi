-- ============================================================
-- BrainboxApi V34: teacher content management
-- Contract: BrainBox/docs/ongoing/api_content_changes.md and doc 04 section 2
-- ============================================================

-- Teacher material types (PDF/EPUB/PLAINTEXT/FLASHCARDS) exceed the original
-- learning_content CHECK; keep the canonical column valid and store the client
-- label alongside it.
ALTER TABLE learning_content ADD COLUMN content_type_label varchar(16);

ALTER TABLE learning_posts ADD COLUMN cbc_strand varchar(128);
ALTER TABLE learning_posts ADD COLUMN cbc_sub_strand varchar(128);
ALTER TABLE learning_posts ADD COLUMN custom_subject_name varchar(128);

-- Teacher content drafts (client-supplied id, offline upsert idempotent).
CREATE TABLE content_drafts (
    id                  uuid PRIMARY KEY,
    client_id           varchar(80) NOT NULL,
    teacher_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    c_type              varchar(16) NOT NULL,
    title               varchar(255) NOT NULL,
    description         text,
    subject             varchar(64),
    custom_subject_name varchar(128),
    grade_level         varchar(64),
    topic               varchar(255),
    body                text,
    media_urls          text,
    tags                text,
    cbc_strands         text,
    difficulty          integer NOT NULL DEFAULT 1,
    estimated_minutes   integer NOT NULL DEFAULT 0,
    last_modified       timestamp with time zone NOT NULL DEFAULT now(),
    author_name         varchar(160),
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_content_draft_client ON content_drafts (client_id);
CREATE INDEX ix_content_drafts_teacher ON content_drafts (teacher_id, last_modified);
