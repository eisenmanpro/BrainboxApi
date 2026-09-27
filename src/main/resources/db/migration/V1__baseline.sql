-- Brainbox API baseline schema.
--
-- This is the single baseline for a deployment: the platform has never had a live user
-- base, so the pre-release chain was collapsed into one file. Every statement below ran in
-- order as V1..V92 before the collapse; nothing was rewritten, only concatenated.
-- The integration suite recreates the schema from this file on every run
-- (Flyway + ddl-auto=validate), which is what proves it is complete.
--
-- Never edit a statement that has shipped. Start a V2__ migration instead.

-- ===========================================================================
-- V1__core_identity
-- ===========================================================================

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

-- ===========================================================================
-- V2__teacher_codes
-- ===========================================================================

-- ============================================================
-- BrainboxApi V2: teacher codes (CTC)
-- Contract: docs/backend_contracts/01_AUTH_IDENTITY_AND_ACCESS.md §7
-- Codes are server-issued only; a student may join a teacher via the
-- code (referredByTeacherCode -> joinedTeacherId + school scope).
-- ============================================================

CREATE TABLE teacher_codes (
    id              uuid PRIMARY KEY,
    code            varchar(8) NOT NULL,
    teacher_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id       uuid REFERENCES schools (id),
    active          boolean NOT NULL DEFAULT TRUE,
    created_at      timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_teacher_code ON teacher_codes (code);
CREATE INDEX ix_teacher_codes_teacher ON teacher_codes (teacher_user_id);

-- ===========================================================================
-- V3__admin_identity
-- ===========================================================================

-- ============================================================
-- BrainboxApi V3: admin identity support
-- Contract: docs/backend_contracts/01_AUTH_IDENTITY_AND_ACCESS.md
-- §2.2 (admin user mgmt), §7.2 (teachers + CTC), §9 (schools).
-- ============================================================

ALTER TABLE schools ADD COLUMN county varchar(128);
ALTER TABLE schools ADD COLUMN location varchar(255);
ALTER TABLE schools ADD COLUMN logo_url varchar(512);

-- Teacher subject/profile record; the teacher account itself is a users row
-- and the CTC code lives in teacher_codes (server-issued only).
CREATE TABLE teacher_profiles (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id  uuid REFERENCES schools (id),
    subject    varchar(128),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_teacher_profile_user ON teacher_profiles (user_id);
CREATE INDEX ix_teacher_profiles_school ON teacher_profiles (school_id);

-- ===========================================================================
-- V4__school_active
-- ===========================================================================

-- V4: school active flag (admin DELETE = deactivate, doc 01 §9.2)
ALTER TABLE schools
    ADD COLUMN is_active boolean NOT NULL DEFAULT TRUE;

-- ===========================================================================
-- V5__idempotency
-- ===========================================================================

-- ============================================================
-- BrainboxApi V5: idempotency records
-- Contract: docs/backend_contracts/11_... §8.3/§8.4 + ARCHITECTURE.md Appendix A
-- Caches one response per client-supplied X-Idempotency-Key so replayed
-- offline-sync POSTs (doc 11 §2.2) return the original result instead of
-- executing twice. Body is JSON text; purged after expiry.
-- ============================================================

CREATE TABLE idempotency_records (
    id           uuid PRIMARY KEY,
    key_hash     varchar(64)  NOT NULL,
    method       varchar(8)   NOT NULL,
    path         varchar(255) NOT NULL,
    status       integer      NOT NULL,
    content_type varchar(128),
    body         text,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    expires_at   timestamp with time zone NOT NULL
);

CREATE UNIQUE INDEX uq_idempotency_key ON idempotency_records (key_hash);
CREATE INDEX ix_idempotency_expiry ON idempotency_records (expires_at);

-- ===========================================================================
-- V6__exams
-- ===========================================================================

-- ============================================================
-- BrainboxApi V6: digital exams domain
-- Contract: docs/backend_contracts/02_EXAMS_AND_ASSESSMENTS.md §1-§5
-- Answer keys/options live server-side only; student-facing payloads are
-- stripped by the API layer. JSON lists (options, matchingPairs, answers,
-- questionResults) are stored as text columns for Postgres/H2 parity.
-- ============================================================

CREATE TABLE exams (
    id               uuid PRIMARY KEY,
    title            varchar(255) NOT NULL,
    subject          varchar(128) NOT NULL,
    exam_type        varchar(32)  NOT NULL
                     CHECK (exam_type IN ('DIGITAL','PAST_PAPER','TRADITIONAL','QUIZ')),
    scope            varchar(32)  NOT NULL DEFAULT 'GLOBAL'
                     CHECK (scope IN ('GLOBAL','SCHOOL','SCHOOL_GRADE_CLASS')),
    school_id        uuid REFERENCES schools (id),
    duration_minutes integer NOT NULL,
    question_count   integer NOT NULL DEFAULT 0,
    difficulty       integer NOT NULL DEFAULT 3 CHECK (difficulty BETWEEN 1 AND 5),
    status           varchar(16) NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    exam_year        integer,
    is_mcp           boolean NOT NULL DEFAULT FALSE,
    cover_image_url  varchar(512),
    created_by       uuid NOT NULL REFERENCES users (id),
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_exams_scope_status ON exams (scope, status);
CREATE INDEX ix_exams_subject ON exams (subject);

CREATE TABLE exam_questions (
    id             uuid PRIMARY KEY,
    exam_id        uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    text           text NOT NULL,
    q_type         varchar(32) NOT NULL
                   CHECK (q_type IN ('MCQ','MULTI_SELECT','SHORT_ANSWER','ESSAY',
                                     'TRUE_FALSE','MATCHING','FILL_BLANK','NUMBER_ENTRY')),
    options        text,
    correct_answer text,
    explanation    text,
    points         integer NOT NULL DEFAULT 1,
    difficulty     integer NOT NULL DEFAULT 3 CHECK (difficulty BETWEEN 1 AND 5),
    matching_pairs text,
    topic          varchar(255),
    subtopic       varchar(255),
    order_index    integer NOT NULL DEFAULT 0
);

CREATE INDEX ix_questions_exam ON exam_questions (exam_id, order_index);

CREATE TABLE exam_sessions (
    id                    uuid PRIMARY KEY,
    exam_id               uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    user_id               uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                varchar(16) NOT NULL
                          CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    current_index         integer NOT NULL DEFAULT 0,
    answers               text,
    flagged               text,
    started_at            timestamp with time zone NOT NULL DEFAULT now(),
    completed_at          timestamp with time zone,
    updated_at            timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_exam_session_user_exam ON exam_sessions (user_id, exam_id);

CREATE TABLE exam_submissions (
    id               uuid PRIMARY KEY,
    exam_id          uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    score            integer NOT NULL DEFAULT 0,
    total_points     integer NOT NULL DEFAULT 0,
    percentage       integer NOT NULL DEFAULT 0,
    grade            varchar(16),
    correct_count    integer NOT NULL DEFAULT 0,
    question_count   integer NOT NULL DEFAULT 0,
    time_taken_seconds integer NOT NULL DEFAULT 0,
    submitted_at     timestamp with time zone NOT NULL DEFAULT now(),
    answers          text,
    question_results text,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_exam_submission_user_exam ON exam_submissions (user_id, exam_id);
CREATE INDEX ix_submissions_exam ON exam_submissions (exam_id);

-- ===========================================================================
-- V7__contests
-- ===========================================================================

-- ============================================================
-- BrainboxApi V7: contests domain
-- Contract: docs/backend_contracts/05_STUDENT_SOCIAL_AND_COMPETITION.md §1
-- Status (UPCOMING/ONGOING/COMPLETED) is derived from the time window;
-- questions reuse the exam question shape & are key-stripped for students.
-- ============================================================

CREATE TABLE contests (
    id               uuid PRIMARY KEY,
    title            varchar(255) NOT NULL,
    subject          varchar(128) NOT NULL,
    grade            varchar(64)  NOT NULL,
    start_time       timestamp with time zone NOT NULL,
    end_time         timestamp with time zone NOT NULL,
    entry_fee        integer NOT NULL DEFAULT 0,
    prize            varchar(255),
    max_participants integer,
    difficulty       integer NOT NULL DEFAULT 3 CHECK (difficulty BETWEEN 1 AND 5),
    lifecycle        varchar(16) NOT NULL DEFAULT 'PUBLISHED'
                     CHECK (lifecycle IN ('DRAFT','PUBLISHED','CANCELLED')),
    created_by       uuid NOT NULL REFERENCES users (id),
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_contests_window ON contests (start_time, end_time);

CREATE TABLE contest_questions (
    id             uuid PRIMARY KEY,
    contest_id     uuid NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    text           text NOT NULL,
    q_type         varchar(32) NOT NULL
                   CHECK (q_type IN ('MCQ','MULTI_SELECT','SHORT_ANSWER','ESSAY',
                                     'TRUE_FALSE','MATCHING','FILL_BLANK','NUMBER_ENTRY')),
    options        text,
    correct_answer text,
    explanation    text,
    points         integer NOT NULL DEFAULT 1,
    difficulty     integer NOT NULL DEFAULT 3 CHECK (difficulty BETWEEN 1 AND 5),
    matching_pairs text,
    topic          varchar(255),
    subtopic       varchar(255),
    order_index    integer NOT NULL DEFAULT 0
);

CREATE INDEX ix_contest_questions_contest ON contest_questions (contest_id, order_index);

CREATE TABLE contest_registrations (
    id            uuid PRIMARY KEY,
    contest_id    uuid NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    student_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    registered_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_contest_registration ON contest_registrations (student_id, contest_id);

CREATE TABLE contest_submissions (
    id                uuid PRIMARY KEY,
    contest_id        uuid NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    score             integer NOT NULL DEFAULT 0,
    total_points      integer NOT NULL DEFAULT 0,
    percentage        integer NOT NULL DEFAULT 0,
    correct_count     integer NOT NULL DEFAULT 0,
    question_count    integer NOT NULL DEFAULT 0,
    submitted_at      timestamp with time zone NOT NULL DEFAULT now(),
    answers           text,
    question_results  text,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_contest_submission_user ON contest_submissions (user_id, contest_id);

-- ===========================================================================
-- V8__contest_sessions
-- ===========================================================================

-- ============================================================
-- BrainboxApi V8: contest sessions
-- Contract: docs/backend_contracts/05_STUDENT_SOCIAL_AND_COMPETITION.md §1.5
-- One session per (student, contest); window/timer driven by the contest
-- end time (server authoritative).
-- ============================================================

CREATE TABLE contest_sessions (
    id             uuid PRIMARY KEY,
    contest_id     uuid NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status         varchar(16) NOT NULL
                   CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    current_index  integer NOT NULL DEFAULT 0,
    answers        text,
    started_at     timestamp with time zone NOT NULL DEFAULT now(),
    completed_at   timestamp with time zone,
    updated_at     timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_contest_session_user ON contest_sessions (user_id, contest_id);

-- ===========================================================================
-- V9__learning_hub
-- ===========================================================================

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

-- ===========================================================================
-- V10__learning_reading
-- ===========================================================================

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

-- ===========================================================================
-- V11__classes
-- ===========================================================================

-- ============================================================
-- BrainboxApi V11: teacher classes & roster
-- Contract: docs/backend_contracts/04_... §2.2 + homework prerequisites.
-- Classes are owned by a teacher; memberships make the roster. Ownership
-- (teacher == owner) is enforced server-side on every class endpoint.
-- ============================================================

CREATE TABLE teacher_classes (
    id              uuid PRIMARY KEY,
    teacher_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id       uuid REFERENCES schools (id),
    name            varchar(255) NOT NULL,
    grade_level     varchar(64)  NOT NULL,
    subject         varchar(128) NOT NULL,
    is_active       boolean NOT NULL DEFAULT TRUE,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_teacher_classes_teacher ON teacher_classes (teacher_user_id);

CREATE TABLE class_memberships (
    id         uuid PRIMARY KEY,
    class_id   uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    student_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_class_membership ON class_memberships (class_id, student_id);
CREATE INDEX ix_class_membership_student ON class_memberships (student_id);

-- ===========================================================================
-- V12__homework
-- ===========================================================================

-- ============================================================
-- BrainboxApi V12: homework
-- Contract: BrainboxWeb/docs/backend_contract_homework.md
-- Homework PK = client-generated id (hw_<epochMs>); creates are idempotent
-- upserts keyed on that id. JSON list fields stored as text (PG/H2 parity).
-- ============================================================

CREATE TABLE homework (
    id                     varchar(128) PRIMARY KEY,
    class_id               uuid NOT NULL REFERENCES teacher_classes (id),
    teacher_id             uuid NOT NULL REFERENCES users (id),
    teacher_name           varchar(255) NOT NULL,
    school_id              uuid REFERENCES schools (id),
    title                  varchar(255) NOT NULL,
    description            text NOT NULL,
    subject                varchar(128) NOT NULL,
    grade_level            integer NOT NULL,
    due_date               timestamp with time zone NOT NULL,
    submission_type        varchar(32) NOT NULL
                           CHECK (submission_type IN ('FREE_TEXT','PAST_PAPER_REVIEW',
                                                     'EXAM_QUESTION_SET','CHECKLIST',
                                                     'OFFLINE_PHYSICAL_HANDIN')),
    checklist_items        text,
    grading_mode           varchar(32)
                           CHECK (grading_mode IN ('AUTO_IMMEDIATE','AUTO_POST_COMPLETION','MANUAL')),
    is_past_paper_unlocked boolean NOT NULL DEFAULT FALSE,
    cbc_strand_tag         varchar(64),
    cbc_sub_strand_tag     varchar(60),
    assigned_student_ids   text,
    scope                  varchar(32) NOT NULL DEFAULT 'SCHOOL_GRADE_CLASS'
                           CHECK (scope IN ('GLOBAL','SCHOOL_GRADE','SCHOOL_GRADE_CLASS')),
    is_active              boolean NOT NULL DEFAULT TRUE,
    is_draft               boolean NOT NULL DEFAULT FALSE,
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    updated_at             timestamp with time zone NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_homework_class ON homework (class_id, is_active);

CREATE TABLE homework_submissions (
    id             uuid PRIMARY KEY,
    homework_id    varchar(128) NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    student_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    submission_text text,
    checklist_answers text,
    attachment_url varchar(512),
    status         varchar(16) NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','GRADED','RETURNED')),
    submitted_at   timestamp with time zone NOT NULL DEFAULT now(),
    grade          integer,
    feedback       text,
    cbc_strand_tag varchar(64),
    graded_by      uuid REFERENCES users (id),
    graded_at      timestamp with time zone,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_homework_submission ON homework_submissions (homework_id, student_id);

-- ===========================================================================
-- V13__homework_questions
-- ===========================================================================

-- ============================================================
-- BrainboxApi V13: homework question sets (auto-grading follow-on)
-- Question keys live server-side; AUTO_IMMEDIATE grades on submit,
-- AUTO_POST_COMPLETION reveals when the due date passes.
-- ============================================================

CREATE TABLE homework_questions (
    id             uuid PRIMARY KEY,
    homework_id    varchar(128) NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    text           text NOT NULL,
    q_type         varchar(32) NOT NULL
                   CHECK (q_type IN ('MCQ','MULTI_SELECT','SHORT_ANSWER','ESSAY',
                                     'TRUE_FALSE','MATCHING','FILL_BLANK','NUMBER_ENTRY')),
    options        text,
    correct_answer text,
    explanation    text,
    points         integer NOT NULL DEFAULT 1,
    order_index    integer NOT NULL DEFAULT 0
);

CREATE INDEX ix_homework_questions_hw ON homework_questions (homework_id, order_index);

ALTER TABLE homework_submissions ADD COLUMN answers_json text;

-- ===========================================================================
-- V14__messaging
-- ===========================================================================

-- ============================================================
-- BrainboxApi V14: messaging
-- Contract: docs/backend_contracts/05_... §2
-- One row per delivery; folder 'inbox' for recipients, 'sent' for senders.
-- msg_group = client message id (msg_<ts>_<userId>) for idempotent replay.
-- ============================================================

CREATE TABLE messages (
    id                 uuid PRIMARY KEY,
    msg_group          varchar(128) NOT NULL,
    sender_id          uuid NOT NULL REFERENCES users (id),
    recipient_id       uuid REFERENCES users (id),
    subject            varchar(255),
    body               text NOT NULL,
    attachments        text,
    folder             varchar(8) NOT NULL DEFAULT 'inbox'
                       CHECK (folder IN ('inbox','sent','outbox')),
    is_read            boolean NOT NULL DEFAULT FALSE,
    read_at            timestamp with time zone,
    intended_for_parent boolean NOT NULL DEFAULT FALSE,
    created_at         timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_messages_recipient ON messages (recipient_id, folder, created_at);
CREATE INDEX ix_messages_sender_group ON messages (sender_id, msg_group);

-- ===========================================================================
-- V15__doubt
-- ===========================================================================

-- ============================================================
-- BrainboxApi V15: doubt solving forum
-- Contract: docs/backend_contracts/05_... §3
-- ============================================================

CREATE TABLE doubt_questions (
    id         uuid PRIMARY KEY,
    title      varchar(255) NOT NULL,
    body       text NOT NULL,
    subject    varchar(128) NOT NULL,
    tags       text,
    author_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status     varchar(16) NOT NULL DEFAULT 'OPEN'
               CHECK (status IN ('OPEN','ANSWERED','CLOSED')),
    vote_count integer NOT NULL DEFAULT 0,
    view_count integer NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE TABLE doubt_answers (
    id          uuid PRIMARY KEY,
    question_id uuid NOT NULL REFERENCES doubt_questions (id) ON DELETE CASCADE,
    body        text NOT NULL,
    author_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    author_role varchar(16) NOT NULL CHECK (author_role IN ('TEACHER','STUDENT')),
    is_accepted boolean NOT NULL DEFAULT FALSE,
    vote_count  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_doubt_answers_question ON doubt_answers (question_id, created_at);

CREATE TABLE doubt_votes (
    id          uuid PRIMARY KEY,
    voter_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type varchar(8) NOT NULL CHECK (target_type IN ('question','answer')),
    target_id   uuid NOT NULL,
    direction   integer NOT NULL CHECK (direction IN (-1, 1)),
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_doubt_vote ON doubt_votes (voter_id, target_type, target_id);

-- ===========================================================================
-- V16__profile_settings
-- ===========================================================================

-- ============================================================
-- BrainboxApi V16: user profile settings
-- Contract: BACKEND_BLUEPRINT.md §13 (GET/PATCH /profile/settings/{userId})
-- Mirrors the Android UserSettings model 1:1; every preference is stored so the
-- server (not the device) owns the durable copy.
-- ============================================================

CREATE TABLE user_settings (
    id                             uuid PRIMARY KEY,
    user_id                        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    avatar_url                     varchar(512),
    subjects                       text,
    daily_reminder_enabled         boolean NOT NULL DEFAULT TRUE,
    daily_reminder_time            varchar(16),
    weekly_report_enabled          boolean NOT NULL DEFAULT TRUE,
    preferred_difficulty           varchar(32) NOT NULL DEFAULT 'Medium',
    interest_subjects              text,
    show_on_leaderboard            boolean NOT NULL DEFAULT TRUE,
    share_progress_with_school     boolean NOT NULL DEFAULT TRUE,
    allow_teacher_view             boolean NOT NULL DEFAULT TRUE,
    push_notifications_enabled     boolean NOT NULL DEFAULT TRUE,
    push_contest_reminders         boolean NOT NULL DEFAULT TRUE,
    push_assignment_due            boolean NOT NULL DEFAULT TRUE,
    push_quiz_results              boolean NOT NULL DEFAULT TRUE,
    push_weekly_report             boolean NOT NULL DEFAULT TRUE,
    push_badges                    boolean NOT NULL DEFAULT TRUE,
    sms_reports_enabled            boolean NOT NULL DEFAULT TRUE,
    email_notifications_enabled    boolean NOT NULL DEFAULT FALSE,
    mpesa_number                   varchar(32),
    theme                          varchar(16) NOT NULL DEFAULT 'dark',
    font_size                      varchar(16) NOT NULL DEFAULT 'medium',
    animations_enabled             boolean NOT NULL DEFAULT TRUE,
    haptic_feedback_enabled        boolean NOT NULL DEFAULT TRUE,
    personalized_content_enabled   boolean NOT NULL DEFAULT TRUE,
    ai_adaptive_difficulty_enabled boolean NOT NULL DEFAULT TRUE,
    ai_sensitivity                 integer NOT NULL DEFAULT 3 CHECK (ai_sensitivity BETWEEN 1 AND 5),
    ai_coaching_style              varchar(32) NOT NULL DEFAULT 'Encouraging',
    cbc_pathway                    varchar(32) NOT NULL DEFAULT 'STEM',
    competency_focus               text,
    grades_taught                  text,
    tsc_number                     varchar(64),
    created_at                     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at                     timestamp with time zone NOT NULL DEFAULT now(),
    version                        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_settings_user ON user_settings (user_id);

-- ===========================================================================
-- V17__career
-- ===========================================================================

-- ============================================================
-- BrainboxApi V17: career guidance, school matching & goals
-- Contract: docs/backend_contracts/06_...  §1/§4 + the Android CareerApi/model.
-- Static curriculum mapping (goal -> subjects, orientation pillars) lives in
-- code (CareerCurriculum); these tables hold reference catalogs and user state.
-- ============================================================

CREATE TABLE career_goals (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    goal        varchar(64) NOT NULL,
    target_date timestamp with time zone,
    milestones  text,
    status      varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','ARCHIVED')),
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_career_goals_user ON career_goals (user_id, status);

CREATE TABLE mentors (
    id                   uuid PRIMARY KEY,
    name                 varchar(128) NOT NULL,
    university           varchar(160) NOT NULL,
    course               varchar(160) NOT NULL,
    rating               double precision NOT NULL DEFAULT 4.5,
    available_slots      integer NOT NULL DEFAULT 0,
    education_band_label varchar(120) NOT NULL DEFAULT '',
    created_at           timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE mentor_requests (
    id         uuid PRIMARY KEY,
    mentor_id  uuid NOT NULL REFERENCES mentors (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status     varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_mentor_request ON mentor_requests (mentor_id, user_id);

CREATE TABLE scholarships (
    id               uuid PRIMARY KEY,
    title            varchar(200) NOT NULL,
    provider         varchar(160) NOT NULL,
    amount           varchar(120) NOT NULL,
    deadline         timestamp with time zone NOT NULL,
    external_url     varchar(512) NOT NULL DEFAULT '',
    eligibility_label varchar(200) NOT NULL DEFAULT '',
    thumbnail_url    varchar(512),
    grade_bands      text,
    created_at       timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE matching_schools (
    id             uuid PRIMARY KEY,
    name           varchar(200) NOT NULL,
    location       varchar(160) NOT NULL,
    type           varchar(32) NOT NULL,
    cluster        varchar(8) NOT NULL,
    pathways       text,
    slots          integer NOT NULL DEFAULT 0,
    match_reason   varchar(255) NOT NULL DEFAULT '',
    required_grade varchar(64),
    required_points integer,
    rating         double precision NOT NULL DEFAULT 4.0,
    website        varchar(512),
    created_at     timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE elective_subjects (
    id          uuid PRIMARY KEY,
    name        varchar(160) NOT NULL,
    category    varchar(64) NOT NULL,
    is_core     boolean NOT NULL DEFAULT FALSE,
    description text,
    grade_bands text,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_elective_subject_name ON elective_subjects (name);

CREATE TABLE user_elective_subjects (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject_id uuid NOT NULL REFERENCES elective_subjects (id) ON DELETE CASCADE,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_elective ON user_elective_subjects (user_id, subject_id);

-- ---------------------------------------------------------------- seed data

INSERT INTO mentors (id, name, university, course, rating, available_slots, education_band_label) VALUES
 ('11111111-1111-1111-1111-000000000001', 'Amina Wanjiru', 'University of Nairobi', 'Medicine & Surgery', 4.9, 4, 'University 3rd Year'),
 ('11111111-1111-1111-1111-000000000002', 'Brian Otieno', 'JKUAT', 'Software Engineering', 4.8, 6, 'University 2nd Year'),
 ('11111111-1111-1111-1111-000000000003', 'Cynthia Mwikali', 'Kenyatta University', 'Law', 4.7, 3, 'University 4th Year'),
 ('11111111-1111-1111-1111-000000000004', 'David Kimani', 'Nairobi Polytechnic', 'Electrical Engineering', 4.6, 5, 'Technical Graduate'),
 ('11111111-1111-1111-1111-000000000005', 'Esther Njeri', 'Moi University', 'Education', 4.8, 8, 'Senior Teacher');

INSERT INTO scholarships (id, title, provider, amount, deadline, external_url, eligibility_label, grade_bands) VALUES
 ('22222222-2222-2222-2222-000000000001', 'Wings to Fly', 'Equity Bank', 'Full Secondary Support', '2027-01-15 00:00:00+00', '', 'Open to Grade 6-9', '["UPPER_PRIMARY","JUNIOR_SCHOOL"]'),
 ('22222222-2222-2222-2222-000000000002', 'County Bursary', 'County Government', 'Partial Support', '2026-11-30 00:00:00+00', '', 'Open to all CBC learners', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL"]'),
 ('22222222-2222-2222-2222-000000000003', 'MasterCard Foundation Scholars', 'MasterCard Foundation', 'Full Tuition', '2027-03-31 00:00:00+00', '', 'University level', '["POST_SECONDARY"]'),
 ('22222222-2222-2222-2222-000000000004', 'HELB Loan', 'HELB', 'University Loan', '2027-02-28 00:00:00+00', '', 'Open to Form 4 / Grade 12', '["SENIOR_SCHOOL"]'),
 ('22222222-2222-2222-2222-000000000005', 'STEM Innovators Award', 'KICD', 'Ksh 50,000 Grant', '2026-12-15 00:00:00+00', '', 'Open to Junior & Senior School', '["JUNIOR_SCHOOL","SENIOR_SCHOOL"]');

INSERT INTO matching_schools (id, name, location, type, cluster, pathways, slots, match_reason, required_grade, required_points, rating, website) VALUES
 ('33333333-3333-3333-3333-000000000001', 'Nairobi High School', 'Nairobi', 'Triple', 'C1', '["STEM","SOCIAL_SCIENCES"]', 120, 'Strong STEM and Social Sciences alignment', 'Form 3', 380, 4.8, ''),
 ('33333333-3333-3333-3333-000000000002', 'Mombasa Academy', 'Mombasa', 'Dual', 'C2', '["STEM"]', 80, 'Excellent STEM focus', 'Form 3', 360, 4.6, ''),
 ('33333333-3333-3333-3333-000000000003', 'Kisumu Senior School', 'Kisumu', 'Special', 'C3', '["ARTS_AND_SPORTS_SCIENCE"]', 60, 'Great for Arts and Sports Science', 'Form 3', 320, 4.4, ''),
 ('33333333-3333-3333-3333-000000000004', 'Alliance Girls High School', 'Kiambu', 'Triple', 'C1', '["STEM","SOCIAL_SCIENCES"]', 100, 'National triple-pathway school', 'Form 3', 400, 4.9, ''),
 ('33333333-3333-3333-3333-000000000005', 'Starehe Boys Centre', 'Nairobi', 'Triple', 'C1', '["STEM","TECHNICAL_VOCATIONAL"]', 90, 'Technical and STEM strengths', 'Form 3', 390, 4.8, ''),
 ('33333333-3333-3333-3333-000000000006', 'Kakamega Technical Institute', 'Kakamega', 'Special', 'C4', '["TECHNICAL_VOCATIONAL"]', 150, 'Hands-on TVET pathways', 'Form 2', 250, 4.2, ''),
 ('33333333-3333-3333-3333-000000000007', 'Nakuru Girls High School', 'Nakuru', 'Dual', 'C2', '["SOCIAL_SCIENCES","ARTS_AND_SPORTS_SCIENCE"]', 70, 'Humanities and arts focus', 'Form 3', 350, 4.5, ''),
 ('33333333-3333-3333-3333-000000000008', 'Eldoret Science Academy', 'Uasin Gishu', 'Dual', 'C2', '["STEM"]', 85, 'Science-heavy curriculum', 'Form 3', 370, 4.6, '');

INSERT INTO elective_subjects (id, name, category, is_core, description, grade_bands) VALUES
 ('44444444-4444-4444-4444-000000000001', 'Mathematics', 'STEM', TRUE, 'Core numeracy and problem solving', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000002', 'English', 'Languages', TRUE, 'Language and communication', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000003', 'Kiswahili', 'Languages', TRUE, 'National language literacy', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000004', 'Science & Technology', 'STEM', FALSE, 'Integrated science', '["UPPER_PRIMARY","JUNIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000005', 'Computer Science', 'STEM', FALSE, 'Programming and digital literacy', '["UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000006', 'Physics', 'STEM', FALSE, 'Mechanics, waves and electricity', '["SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000007', 'Chemistry', 'STEM', FALSE, 'Matter and reactions', '["SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000008', 'Biology', 'STEM', FALSE, 'Life sciences', '["SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000009', 'History', 'Arts', FALSE, 'Kenyan and world history', '["JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000010', 'Geography', 'Arts', FALSE, 'Physical and human geography', '["JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000011', 'Business Studies', 'STEM', FALSE, 'Enterprise and commerce', '["JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000012', 'Creative Arts & Craft', 'Arts', FALSE, 'Visual and performing arts', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000013', 'Agriculture', 'STEM', FALSE, 'Farming and agri-business', '["UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000014', 'Pre-Technical Studies', 'STEM', FALSE, 'Workshop and technical skills', '["JUNIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000015', 'Home Science', 'Arts', FALSE, 'Nutrition and consumer skills', '["UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000016', 'Health Education', 'STEM', FALSE, 'Health and wellbeing', '["JUNIOR_SCHOOL","POST_SECONDARY"]'),
 ('44444444-4444-4444-4444-000000000017', 'Physical & Health Education', 'Arts', FALSE, 'Sport and fitness', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000018', 'Social Studies', 'Arts', FALSE, 'Citizenship and society', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000019', 'Religious Education', 'Arts', FALSE, 'Faith and values education', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL"]'),
 ('44444444-4444-4444-4444-000000000020', 'General', 'STEM', FALSE, 'Exploratory studies', '["LOWER_PRIMARY","UPPER_PRIMARY","JUNIOR_SCHOOL","SENIOR_SCHOOL","POST_SECONDARY"]');

-- ===========================================================================
-- V18__interview
-- ===========================================================================

-- ============================================================
-- BrainboxApi V18: mock interviews
-- Contract: docs/backend_contracts/06_... §2 + the Android InterviewApi/models.
-- The server owns the question bank and scores each answer (the client mock
-- shows the exact algorithm the app expects back).
-- ============================================================

CREATE TABLE interview_questions (
    id                uuid PRIMARY KEY,
    type              varchar(48) NOT NULL,
    text              text NOT NULL,
    category          varchar(64) NOT NULL DEFAULT 'General',
    difficulty        integer NOT NULL DEFAULT 1,
    order_index       integer NOT NULL DEFAULT 0,
    sample_answer     text,
    keywords          text,
    min_words         integer NOT NULL DEFAULT 20,
    max_words         integer NOT NULL DEFAULT 200,
    structure_phrases text,
    rubric_type       varchar(16) NOT NULL DEFAULT 'NONE',
    company_focus     varchar(160),
    school_focus      varchar(160),
    created_at        timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_interview_questions_type ON interview_questions (type, order_index);

CREATE TABLE interview_sessions (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         varchar(48) NOT NULL,
    mode         varchar(16) NOT NULL,
    difficulty   integer NOT NULL DEFAULT 1,
    status       varchar(16) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED','ABANDONED')),
    score        double precision,
    started_at   timestamp with time zone NOT NULL DEFAULT now(),
    completed_at timestamp with time zone,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_interview_sessions_user ON interview_sessions (user_id, started_at);

CREATE TABLE interview_session_questions (
    id          uuid PRIMARY KEY,
    session_id  uuid NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE,
    question_id uuid NOT NULL REFERENCES interview_questions (id) ON DELETE CASCADE,
    order_index integer NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_interview_session_question ON interview_session_questions (session_id, question_id);

CREATE TABLE interview_answers (
    id                       uuid PRIMARY KEY,
    session_id               uuid NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE,
    question_id              uuid NOT NULL REFERENCES interview_questions (id) ON DELETE CASCADE,
    transcribed_text         text NOT NULL DEFAULT '',
    word_count               integer NOT NULL DEFAULT 0,
    clarity_score            integer NOT NULL DEFAULT 0,
    filler_word_count        integer NOT NULL DEFAULT 0,
    filler_replacements      text,
    pace                     integer NOT NULL DEFAULT 0,
    keyword_match_count      integer NOT NULL DEFAULT 0,
    total_keywords           integer NOT NULL DEFAULT 0,
    structure_phrases_found  integer NOT NULL DEFAULT 0,
    feedback                 text NOT NULL DEFAULT '',
    score                    integer NOT NULL DEFAULT 0,
    rubric_json              text,
    diction_score            integer NOT NULL DEFAULT 0,
    pronunciation_score      integer NOT NULL DEFAULT 0,
    emotion                  varchar(32),
    emotion_confidence       double precision,
    created_at               timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_interview_answer ON interview_answers (session_id, question_id);

-- ---------------------------------------------------------------- question bank

INSERT INTO interview_questions (id, type, text, category, difficulty, order_index, sample_answer, keywords, min_words, max_words, structure_phrases, rubric_type) VALUES
 ('55555555-6666-7777-8888-000000000001', 'UNIVERSITY_INTERVIEW', 'Tell me about yourself.', 'Introduction', 1, 1, 'I am a dedicated student passionate about computer science...', '["passionate","dedicated","goals","background","experience"]', 30, 150, '["firstly","secondly","finally","in conclusion","for example"]', 'STAR'),
 ('55555555-6666-7777-8888-000000000002', 'UNIVERSITY_INTERVIEW', 'Why do you want to study at this university?', 'Motivation', 1, 2, NULL, '["research","facilities","program","reputation","alumni"]', 25, 120, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000003', 'UNIVERSITY_INTERVIEW', 'What are your strengths and weaknesses?', 'Self-awareness', 1, 3, NULL, '["strength","weakness","improve","learn","adapt"]', 40, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000004', 'UNIVERSITY_INTERVIEW', 'Describe a challenge you overcame.', 'Behavioural', 1, 4, NULL, '["challenge","overcame","learned","problem","solution"]', 50, 250, '["firstly","secondly","finally","in conclusion","for example"]', 'SOAR'),
 ('55555555-6666-7777-8888-000000000005', 'JOB_INTERVIEW', 'Tell me about yourself.', 'Introduction', 1, 1, NULL, '["experience","skills","achievements","role","company"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000006', 'JOB_INTERVIEW', 'Why do you want this job?', 'Motivation', 1, 2, NULL, '["mission","growth","contribute","values","team"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000007', 'JOB_INTERVIEW', 'Describe a difficult work situation.', 'Behavioural', 1, 3, NULL, '["situation","action","result","problem","solution"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'STAR'),
 ('55555555-6666-7777-8888-000000000008', 'JOB_INTERVIEW', 'What are your salary expectations?', 'Negotiation', 1, 4, NULL, '["market","value","negotiable","experience","range"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000009', 'LANGUAGE_SPEAKING', 'Introduce yourself in English.', 'Speaking', 1, 1, NULL, '["name","background","interests","hobby","study"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000010', 'LANGUAGE_SPEAKING', 'Describe your daily routine.', 'Speaking', 1, 2, NULL, '["morning","afternoon","evening","activity","time"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000011', 'LANGUAGE_SPEAKING', 'What are your hobbies?', 'Speaking', 1, 3, NULL, '["hobby","enjoy","free time","weekend","sport"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000012', 'PRESENTATION_PRACTICE', 'Introduce your presentation topic.', 'Structure', 1, 1, NULL, '["topic","purpose","audience","structure"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000013', 'PRESENTATION_PRACTICE', 'Explain the main point of your presentation.', 'Content', 1, 2, NULL, '["main","point","key","message"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE'),
 ('55555555-6666-7777-8888-000000000014', 'PRESENTATION_PRACTICE', 'How would you engage your audience?', 'Delivery', 1, 3, NULL, '["engage","audience","interactive","question"]', 20, 200, '["firstly","secondly","finally","in conclusion","for example"]', 'NONE');

-- ===========================================================================
-- V19__mastery
-- ===========================================================================

-- ============================================================
-- BrainboxApi V19: topic mastery tracking
-- Contract: docs/backend_contracts/03_... §6 + the Android MasteryApi/models.
-- Mastery is cumulative per (user, topic): the server accumulates attempts and
-- recomputes the score; the client renders it read-only.
-- ============================================================

CREATE TABLE topic_mastery (
    id                uuid PRIMARY KEY,
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    topic_id          varchar(160) NOT NULL,
    topic_name        varchar(200) NOT NULL,
    subject           varchar(128) NOT NULL,
    score             double precision NOT NULL DEFAULT 0,
    previous_score    double precision NOT NULL DEFAULT 0,
    attempts_count    integer NOT NULL DEFAULT 0,
    questions_attempted integer NOT NULL DEFAULT 0,
    correct_answers   integer NOT NULL DEFAULT 0,
    total_time_seconds bigint NOT NULL DEFAULT 0,
    last_practiced    timestamp with time zone NOT NULL DEFAULT now(),
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_topic_mastery ON topic_mastery (user_id, topic_id);
CREATE INDEX ix_topic_mastery_user ON topic_mastery (user_id, subject);

-- ===========================================================================
-- V20__achievements_rewards
-- ===========================================================================

-- ============================================================
-- BrainboxApi V20: achievements, XP, badges and rewards
-- Contract: docs/backend_contracts/03_... §7/§8 + the Android AchievementsApi/models.
-- ============================================================

CREATE TABLE user_achievements (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    total_xp       integer NOT NULL DEFAULT 0,
    streak_freezes integer NOT NULL DEFAULT 0,
    grace_days     integer NOT NULL DEFAULT 0,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_achievements ON user_achievements (user_id);

CREATE TABLE xp_events (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount        integer NOT NULL,
    activity_type varchar(64) NOT NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_xp_events_user ON xp_events (user_id, created_at);

CREATE TABLE badges (
    id          uuid PRIMARY KEY,
    title       varchar(128) NOT NULL,
    icon        varchar(64) NOT NULL,
    description varchar(255) NOT NULL DEFAULT '',
    tier        varchar(16),
    cbc_strand  varchar(32),
    is_secret   boolean NOT NULL DEFAULT FALSE,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE user_badges (
    id        uuid PRIMARY KEY,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    badge_id  uuid NOT NULL REFERENCES badges (id) ON DELETE CASCADE,
    earned_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_badge ON user_badges (user_id, badge_id);

CREATE TABLE rewards (
    id          uuid PRIMARY KEY,
    title       varchar(160) NOT NULL,
    description text NOT NULL DEFAULT '',
    xp_cost     integer NOT NULL,
    icon        varchar(64) NOT NULL,
    type        varchar(32) NOT NULL,
    category    varchar(64) NOT NULL DEFAULT '',
    available   boolean NOT NULL DEFAULT TRUE,
    valid_until timestamp with time zone,
    image_url   varchar(512),
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE user_rewards (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reward_id   uuid NOT NULL REFERENCES rewards (id) ON DELETE CASCADE,
    coupon_code varchar(32) NOT NULL,
    redeemed_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_reward ON user_rewards (user_id, reward_id);

INSERT INTO badges (id, title, icon, description, tier, cbc_strand) VALUES
 ('66666666-6666-6666-6666-000000000001', 'Early Bird', 'EB', 'Practise before 8am.', 'NOVICE', 'ORAL_EXPRESSION'),
 ('66666666-6666-6666-6666-000000000002', 'Quiz Master', 'QM', 'Score 80%+ in five quizzes.', 'DEVELOPING', 'LOGICAL_SEQUENCING'),
 ('66666666-6666-6666-6666-000000000003', 'Speed Demon', 'SD', 'Finish a quiz under half the time.', 'PROFICIENT', 'CRITICAL_THINKING'),
 ('66666666-6666-6666-6666-000000000004', 'Collaboration Star', 'CS', 'Help classmates in the doubt forum.', 'ADVANCED', 'COLLABORATION'),
 ('66666666-6666-6666-6666-000000000005', 'Creative Thinker', 'CT', 'Submit an outstanding CBC project.', 'MASTER', 'CREATIVITY'),
 ('66666666-6666-6666-6666-000000000006', 'Digital Explorer', 'DE', 'Complete ten learning posts.', 'NOVICE', 'DIGITAL_LITERACY'),
 ('66666666-6666-6666-6666-000000000007', 'Helper', 'HL', 'Answer five questions for peers.', 'DEVELOPING', 'COLLABORATION'),
 ('66666666-6666-6666-6666-000000000008', 'Contest Winner', 'CW', 'Win a BrainBox contest.', 'ADVANCED', 'CRITICAL_THINKING'),
 ('66666666-6666-6666-6666-000000000009', 'Perfect Week', 'PW', 'Practise every day for a week.', 'PROFICIENT', 'LOGICAL_SEQUENCING'),
 ('66666666-6666-6666-6666-000000000010', 'Top 10%', 'T1', 'Rank in the national top 10%.', 'MASTER', 'DIGITAL_LITERACY');

INSERT INTO rewards (id, title, description, xp_cost, icon, type, category) VALUES
 ('77777777-7777-7777-7777-000000000001', '1 Month Premium', 'Unlock all premium features for 30 days.', 5000, 'premium', 'PREMIUM_ACCESS', 'Premium'),
 ('77777777-7777-7777-7777-000000000002', 'Pizza Voucher', 'Ksh 500 voucher for Pizza Inn.', 10000, 'food', 'FOOD', 'Food'),
 ('77777777-7777-7777-7777-000000000003', 'Book Store Coupon', 'Ksh 1000 coupon for Text Book Centre.', 15000, 'education', 'EDUCATION', 'Education'),
 ('77777777-7777-7777-7777-000000000004', 'BrainBox Hoodie', 'Exclusive branded hoodie.', 50000, 'merchandise', 'MERCHANDISE', 'Merchandise');

-- ===========================================================================
-- V21__live_classes
-- ===========================================================================

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

-- ===========================================================================
-- V22__cbc_projects
-- ===========================================================================

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

-- ===========================================================================
-- V23__notifications_news
-- ===========================================================================

-- ============================================================
-- BrainboxApi V23: notifications + news
-- Contract: docs/backend_contracts/05_... §5/§6 + the Android AppNotificationApi/NewsApi.
-- System notifications (subscription lifecycle reminders) are materialized on read
-- and deduplicated by (user_id, dedupe_key).
-- ============================================================

CREATE TABLE notifications (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title        varchar(220) NOT NULL,
    message      text NOT NULL,
    type         varchar(24) NOT NULL,
    urgency      varchar(16) NOT NULL DEFAULT 'NORMAL',
    priority     varchar(16) NOT NULL DEFAULT 'NORMAL',
    action_route varchar(64),
    action_label varchar(64),
    metadata     text,
    is_read      boolean NOT NULL DEFAULT FALSE,
    read_at      timestamp with time zone,
    is_archived  boolean NOT NULL DEFAULT FALSE,
    dedupe_key   varchar(200),
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_notifications_user ON notifications (user_id, is_archived, created_at);
CREATE UNIQUE INDEX uq_notifications_dedupe ON notifications (user_id, dedupe_key);

CREATE TABLE news_items (
    id           uuid PRIMARY KEY,
    title        varchar(240) NOT NULL,
    content      text NOT NULL DEFAULT '',
    image_url    varchar(512),
    category     varchar(64) NOT NULL DEFAULT 'General',
    author_id    uuid REFERENCES users (id) ON DELETE SET NULL,
    published_at timestamp with time zone,
    status       varchar(16) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('DRAFT','PUBLISHED')),
    tags         text,
    likes        integer NOT NULL DEFAULT 0,
    dislikes     integer NOT NULL DEFAULT 0,
    comment_count integer NOT NULL DEFAULT 0,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_news_status_published ON news_items (status, published_at);

-- ===========================================================================
-- V24__hardening_alignment
-- ===========================================================================

-- ============================================================
-- BrainboxApi V24: app-hardening contract alignment
--  - schools: public list/detail shape + reviews/join-requests/reports
--  - news: author + public reads + comments/votes/reports
--  - CBC: public (guest) votes/comments/tracks/reports + viewer keys
-- Contracts: BrainBox/docs/ongoing/api_schools_changes.md,
--            api_news_changes.md, api_cbc_public_changes.md
-- ============================================================

-- ---------------------------------------------------------------- schools
ALTER TABLE schools ADD COLUMN rating double precision NOT NULL DEFAULT 0;
ALTER TABLE schools ADD COLUMN reviews_count integer NOT NULL DEFAULT 0;
ALTER TABLE schools ADD COLUMN placement_rate integer NOT NULL DEFAULT 0;
ALTER TABLE schools ADD COLUMN school_rank integer NOT NULL DEFAULT 0;
ALTER TABLE schools ADD COLUMN logo_urls text;

CREATE TABLE school_details (
    school_id                uuid PRIMARY KEY REFERENCES schools (id) ON DELETE CASCADE,
    type                     varchar(64) NOT NULL DEFAULT '',
    grade_levels             varchar(64) NOT NULL DEFAULT '',
    hours                    varchar(120) NOT NULL DEFAULT '',
    image_urls               text,
    phone                    varchar(64) NOT NULL DEFAULT '',
    email                    varchar(160) NOT NULL DEFAULT '',
    website                  varchar(255) NOT NULL DEFAULT '',
    social_media             text,
    admission_contact        varchar(160) NOT NULL DEFAULT '',
    transportation           varchar(255) NOT NULL DEFAULT '',
    curriculum               varchar(160) NOT NULL DEFAULT '',
    teacher_ratio            varchar(64) NOT NULL DEFAULT '',
    class_size               varchar(64) NOT NULL DEFAULT '',
    programs                 text,
    graduation_requirements  text,
    test_scores              varchar(160) NOT NULL DEFAULT '',
    college_acceptance       varchar(160) NOT NULL DEFAULT '',
    faculty_count            integer NOT NULL DEFAULT 0,
    advanced_degrees_percentage integer NOT NULL DEFAULT 0,
    average_experience       integer NOT NULL DEFAULT 0,
    support_staff            text,
    total_enrollment         integer NOT NULL DEFAULT 0,
    gender_breakdown         varchar(120) NOT NULL DEFAULT '',
    diversity_stats          varchar(255) NOT NULL DEFAULT '',
    ell_population           varchar(64) NOT NULL DEFAULT '',
    special_needs_support    varchar(160) NOT NULL DEFAULT '',
    extracurriculars         text,
    facilities               text,
    tuition                  text,
    parent_involvement       text,
    enrollment_deadlines     varchar(160) NOT NULL DEFAULT '',
    enrollment_documents     text,
    enrollment_exams         varchar(200) NOT NULL DEFAULT '',
    enrollment_tour_link     varchar(255) NOT NULL DEFAULT '',
    important_dates          text,
    created_at               timestamp with time zone NOT NULL DEFAULT now(),
    updated_at               timestamp with time zone NOT NULL DEFAULT now(),
    version                  bigint NOT NULL DEFAULT 0
);

CREATE TABLE school_reviews (
    id          uuid PRIMARY KEY,
    school_id   uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name   varchar(160) NOT NULL,
    rating      integer NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     text NOT NULL,
    review_date date NOT NULL,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_school_reviews_school ON school_reviews (school_id, created_at);

CREATE TABLE school_join_requests (
    id               uuid PRIMARY KEY,
    school_id        uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    student_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_name     varchar(160) NOT NULL,
    grade_level      varchar(64) NOT NULL,
    admission_number varchar(64),
    status           varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    reference        varchar(32) NOT NULL,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_school_join_school ON school_join_requests (school_id, status);

CREATE TABLE school_reports (
    id         uuid PRIMARY KEY,
    school_id  uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason     varchar(64) NOT NULL,
    detail     text,
    reference  varchar(32) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_school_reports_school ON school_reports (school_id, created_at);

-- ---------------------------------------------------------------- news
ALTER TABLE news_items ADD COLUMN author varchar(160);

CREATE TABLE news_comments (
    id          uuid PRIMARY KEY,
    news_id     uuid NOT NULL REFERENCES news_items (id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name   varchar(160) NOT NULL,
    user_avatar varchar(512),
    content     text NOT NULL,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_news_comments_news ON news_comments (news_id, created_at);

CREATE TABLE news_votes (
    id         uuid PRIMARY KEY,
    news_id    uuid NOT NULL REFERENCES news_items (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    vote_type  varchar(16) NOT NULL CHECK (vote_type IN ('UPVOTE','DOWNVOTE')),
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_news_vote ON news_votes (news_id, user_id);

CREATE TABLE news_reports (
    id         uuid PRIMARY KEY,
    news_id    uuid NOT NULL REFERENCES news_items (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason     varchar(64) NOT NULL,
    details    text,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_news_reports_news ON news_reports (news_id, created_at);

-- ------------------------------------------------- CBC public (guest) identity
ALTER TABLE cbc_project_votes ADD COLUMN voter_key varchar(80);
ALTER TABLE cbc_project_votes ALTER COLUMN user_id DROP NOT NULL;
UPDATE cbc_project_votes SET voter_key = 'user:' || user_id;
ALTER TABLE cbc_project_votes ALTER COLUMN voter_key SET NOT NULL;
DROP INDEX uq_cbc_project_vote;
CREATE UNIQUE INDEX uq_cbc_project_voter ON cbc_project_votes (project_id, voter_key);

ALTER TABLE cbc_project_views ADD COLUMN viewer_key varchar(80);
ALTER TABLE cbc_project_views ALTER COLUMN user_id DROP NOT NULL;
UPDATE cbc_project_views SET viewer_key = 'user:' || user_id;
ALTER TABLE cbc_project_views ALTER COLUMN viewer_key SET NOT NULL;
DROP INDEX uq_cbc_project_view;
CREATE UNIQUE INDEX uq_cbc_project_viewer ON cbc_project_views (project_id, viewer_key);

ALTER TABLE cbc_project_comments ADD COLUMN guest_id varchar(64);
ALTER TABLE cbc_project_comments ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE cbc_project_comments ALTER COLUMN user_role DROP NOT NULL;

CREATE TABLE cbc_project_tracks (
    id         uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES cbc_projects (id) ON DELETE CASCADE,
    email      varchar(200) NOT NULL,
    guest_id   varchar(64),
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cbc_project_track ON cbc_project_tracks (project_id, email);

CREATE TABLE cbc_project_reports (
    id         uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES cbc_projects (id) ON DELETE CASCADE,
    guest_id   varchar(64) NOT NULL,
    reason     varchar(64) NOT NULL,
    details    text,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cbc_project_report ON cbc_project_reports (project_id, guest_id, reason);

-- ===========================================================================
-- V25__study_tools
-- ===========================================================================

-- ============================================================
-- BrainboxApi V25: study tools (doc 03 §9)
-- Server-owned study sessions and server-computed study insights. A unique key
-- on (user, subject, topic, start_time) makes replayed offline sync idempotent.
-- ============================================================

CREATE TABLE study_sessions (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject          varchar(128) NOT NULL DEFAULT '',
    topic            varchar(200) NOT NULL DEFAULT '',
    start_time       timestamp with time zone NOT NULL,
    end_time         timestamp with time zone NOT NULL,
    duration_minutes integer NOT NULL DEFAULT 0,
    focus_score      integer NOT NULL DEFAULT 0 CHECK (focus_score BETWEEN 0 AND 100),
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_study_session ON study_sessions (user_id, subject, topic, start_time);
CREATE INDEX ix_study_sessions_user ON study_sessions (user_id, start_time);

-- ===========================================================================
-- V26__homework_past_paper
-- ===========================================================================

-- ============================================================
-- BrainboxApi V26: homework attachments & past-paper linking
-- Contract: BrainboxWeb/docs/backend_contract_homework.md
-- ============================================================

ALTER TABLE homework ADD COLUMN related_paper_code varchar(64);
ALTER TABLE homework ADD COLUMN related_document_id varchar(64);

-- ===========================================================================
-- V27__class_group_chat
-- ===========================================================================

-- ============================================================
-- BrainboxApi V27: teacher class-group chat (doc 04 §12)
-- REST surface; realtime WebSocket transport arrives with Phase 6.
-- ============================================================

CREATE TABLE class_groups (
    id                   uuid PRIMARY KEY,
    class_id             uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    teacher_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_name         varchar(160) NOT NULL,
    name                 varchar(200) NOT NULL,
    description          varchar(500),
    is_announcement_mode boolean NOT NULL DEFAULT FALSE,
    teacher_last_read_at timestamp with time zone,
    teacher_muted_until  timestamp with time zone,
    created_at           timestamp with time zone NOT NULL DEFAULT now(),
    updated_at           timestamp with time zone NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_class_groups_teacher ON class_groups (teacher_id, updated_at);

CREATE TABLE class_group_members (
    id          uuid PRIMARY KEY,
    group_id    uuid NOT NULL REFERENCES class_groups (id) ON DELETE CASCADE,
    member_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    member_name varchar(160) NOT NULL,
    member_role varchar(16) NOT NULL,
    muted_until timestamp with time zone,
    joined_at   timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_class_group_member ON class_group_members (group_id, member_id);

CREATE TABLE class_group_messages (
    id              uuid PRIMARY KEY,
    group_id        uuid NOT NULL REFERENCES class_groups (id) ON DELETE CASCADE,
    sender_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sender_name     varchar(160) NOT NULL,
    sender_role     varchar(16) NOT NULL,
    text            text NOT NULL,
    is_pinned       boolean NOT NULL DEFAULT FALSE,
    is_announcement boolean NOT NULL DEFAULT FALSE,
    reply_to_id     uuid,
    attachments     text,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_class_group_messages ON class_group_messages (group_id, created_at);

CREATE TABLE class_group_polls (
    id         uuid PRIMARY KEY,
    group_id   uuid NOT NULL REFERENCES class_groups (id) ON DELETE CASCADE,
    created_by uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    question   varchar(500) NOT NULL,
    options    text NOT NULL,
    is_active  boolean NOT NULL DEFAULT TRUE,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE TABLE class_group_poll_votes (
    id           uuid PRIMARY KEY,
    poll_id      uuid NOT NULL REFERENCES class_group_polls (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    option_index integer NOT NULL,
    voted_at     timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_class_group_poll_vote ON class_group_poll_votes (poll_id, user_id);

-- ===========================================================================
-- V28__class_chat_transport
-- ===========================================================================

-- ============================================================
-- BrainboxApi V28: class-group chat transport (parent/student + idempotency)
-- Contract: BrainBox/docs/ongoing/api_class_group_chat_changes.md
-- ============================================================

-- Stable client id per message for offline replay de-duplication.
ALTER TABLE class_group_messages ADD COLUMN client_message_id varchar(128);
CREATE UNIQUE INDEX uq_class_group_message_client ON class_group_messages (group_id, client_message_id);

-- Per-caller read markers (teacher, parent and student all tracked uniformly).
CREATE TABLE class_group_reads (
    id           uuid PRIMARY KEY,
    group_id     uuid NOT NULL REFERENCES class_groups (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_class_group_read ON class_group_reads (group_id, user_id);

-- Superseded by class_group_reads.
ALTER TABLE class_groups DROP COLUMN teacher_last_read_at;

-- Chat deep links (class_group_chat/{groupId}/{groupName}) exceed the original width.
ALTER TABLE notifications ALTER COLUMN action_route TYPE varchar(255);

-- ===========================================================================
-- V29__traditional_exams
-- ===========================================================================

-- ============================================================
-- BrainboxApi V29: traditional (paper) exam engine + student reports
-- Contract: docs/backend_contracts/10_TRADITIONAL_EXAMS_GRADEBOOKS_AND_ANALYTICS.md,
--           docs/ongoing/api_student_reports_changes.md
-- ============================================================

CREATE TABLE traditional_exams (
    id                  uuid PRIMARY KEY,
    title               varchar(255) NOT NULL,
    term                varchar(16) NOT NULL,
    grade_level         varchar(64) NOT NULL,
    exam_year           integer NOT NULL,
    status              varchar(32) NOT NULL,
    max_score           integer NOT NULL DEFAULT 100,
    auto_generated      boolean NOT NULL DEFAULT TRUE,
    school_id           uuid,
    created_by          uuid NOT NULL,
    finalized_at        timestamp with time zone,
    finalized_by        uuid,
    coordinator_remarks text,
    published_at        timestamp with time zone,
    published_by        uuid,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_traditional_exam_status CHECK (status IN ('PENDING','IN_PROGRESS','CONFIRMED','PRE_FINAL','FINALIZED','PUBLISHED')),
    CONSTRAINT ck_traditional_exam_term CHECK (term IN ('TERM_1','TERM_2','TERM_3'))
);

CREATE INDEX ix_traditional_exams_scope ON traditional_exams (school_id, grade_level, term, exam_year);
CREATE INDEX ix_traditional_exams_status ON traditional_exams (status, published_at);

CREATE TABLE traditional_exam_subjects (
    id           uuid PRIMARY KEY,
    exam_id      uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    subject_id   varchar(64) NOT NULL,
    name         varchar(128) NOT NULL,
    max_score    integer NOT NULL,
    subject_type varchar(16) NOT NULL DEFAULT 'SINGLE',
    is_optional  boolean NOT NULL DEFAULT FALSE,
    components   text,
    order_index  integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_traditional_subject_type CHECK (subject_type IN ('SINGLE','COMBINED')),
    CONSTRAINT uq_traditional_exam_subject UNIQUE (exam_id, subject_id)
);

CREATE TABLE traditional_marks (
    id                   uuid PRIMARY KEY,
    exam_id              uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    student_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject_id           varchar(64) NOT NULL,
    raw_score            integer NOT NULL DEFAULT 0,
    percentage           double precision,
    grade_band           varchar(8),
    component_scores     text,
    confirmed_by_teacher boolean NOT NULL DEFAULT FALSE,
    confirmed_at         timestamp with time zone,
    entered_by           uuid,
    entered_at           timestamp with time zone NOT NULL DEFAULT now(),
    created_at           timestamp with time zone NOT NULL DEFAULT now(),
    updated_at           timestamp with time zone NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_traditional_mark UNIQUE (exam_id, student_id, subject_id)
);

CREATE INDEX ix_traditional_marks_student ON traditional_marks (student_id, exam_id);
CREATE INDEX ix_traditional_marks_subject ON traditional_marks (exam_id, subject_id);

-- Coordinator-configured subject catalogue per grade (reused across exams).
CREATE TABLE traditional_subject_configs (
    id           uuid PRIMARY KEY,
    school_id    uuid,
    grade_level  varchar(64) NOT NULL,
    subject_id   varchar(64) NOT NULL,
    name         varchar(128) NOT NULL,
    max_score    integer NOT NULL,
    subject_type varchar(16) NOT NULL DEFAULT 'SINGLE',
    is_optional  boolean NOT NULL DEFAULT FALSE,
    components   text,
    order_index  integer NOT NULL DEFAULT 0
);

CREATE INDEX ix_traditional_subject_configs_scope ON traditional_subject_configs (school_id, grade_level);

-- Coordinator-configured grading bands per grade.
CREATE TABLE traditional_grading_configs (
    id            uuid PRIMARY KEY,
    school_id     uuid,
    grade_level   varchar(64) NOT NULL,
    bands         text NOT NULL,
    overall_bands text
);

CREATE INDEX ix_traditional_grading_configs_scope ON traditional_grading_configs (school_id, grade_level);

-- Per-teacher mark confirmation for an exam/grade.
CREATE TABLE traditional_confirmations (
    id           uuid PRIMARY KEY,
    exam_id      uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    grade_level  varchar(64) NOT NULL,
    confirmed_at timestamp with time zone
);

CREATE UNIQUE INDEX uq_traditional_confirmation ON traditional_confirmations (exam_id, teacher_id, grade_level);

CREATE TABLE traditional_edit_requests (
    id                  uuid PRIMARY KEY,
    exam_id             uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    requester_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject_id          varchar(64) NOT NULL,
    old_score           integer NOT NULL,
    new_score           integer NOT NULL,
    reason              text NOT NULL,
    status              varchar(16) NOT NULL DEFAULT 'PENDING',
    coordinator_comment text,
    reviewed_by         uuid,
    reviewed_at         timestamp with time zone,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_traditional_edit_status CHECK (status IN ('PENDING','APPROVED','DENIED'))
);

CREATE INDEX ix_traditional_edit_requests_exam ON traditional_edit_requests (exam_id, status);

CREATE TABLE traditional_edit_permissions (
    id           uuid PRIMARY KEY,
    exam_id      uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    student_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    granted_by   uuid NOT NULL,
    granted_at   timestamp with time zone NOT NULL DEFAULT now(),
    expires_at   timestamp with time zone NOT NULL,
    used         boolean NOT NULL DEFAULT FALSE
);

CREATE INDEX ix_traditional_edit_permissions_lookup ON traditional_edit_permissions (exam_id, student_id, teacher_id);

-- ===========================================================================
-- V30__teacher_attendance
-- ===========================================================================

-- ============================================================
-- BrainboxApi V30: teacher attendance register
-- Contract: BrainBox/docs/ongoing/api_attendance_changes.md and doc 04 §4
-- ============================================================

CREATE TABLE attendance_records (
    id                      uuid PRIMARY KEY,
    class_id                uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    student_id              uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id               uuid,
    attendance_date         date NOT NULL,
    status                  varchar(16) NOT NULL,
    notes                   text,
    recorded_by             uuid,
    recorded_by_name        varchar(160),
    is_auto_from_live_class boolean NOT NULL DEFAULT FALSE,
    created_at              timestamp with time zone NOT NULL DEFAULT now(),
    updated_at              timestamp with time zone NOT NULL DEFAULT now(),
    version                 bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_attendance_status CHECK (status IN ('PRESENT','ABSENT','LATE','EXCUSED'))
);

-- A register is idempotent per (class, calendar day, student); replayed offline
-- registers converge instead of duplicating.
CREATE UNIQUE INDEX uq_attendance_record ON attendance_records (class_id, attendance_date, student_id);
CREATE INDEX ix_attendance_class_date ON attendance_records (class_id, attendance_date);
CREATE INDEX ix_attendance_student_date ON attendance_records (student_id, attendance_date);

-- ===========================================================================
-- V31__gradebook
-- ===========================================================================

-- ============================================================
-- BrainboxApi V31: teacher gradebook (assessments + manual grades)
-- Contract: BrainBox/docs/ongoing/api_gradebook_changes.md
-- ============================================================

CREATE TABLE gradebook_assessments (
    id                     uuid PRIMARY KEY,
    client_id              varchar(80) NOT NULL,
    class_id               uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    title                  varchar(200) NOT NULL,
    assessment_type        varchar(16) NOT NULL,
    max_score              integer NOT NULL,
    date_assigned          timestamp with time zone NOT NULL,
    cbc_strand_tag         varchar(128),
    term                   varchar(16) NOT NULL,
    is_published           boolean NOT NULL DEFAULT FALSE,
    counts_toward_average  boolean NOT NULL DEFAULT TRUE,
    created_by             uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id              uuid,
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    updated_at             timestamp with time zone NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_gradebook_assessment_type CHECK (assessment_type IN ('HOMEWORK','EXAM','QUIZ','CONTEST')),
    CONSTRAINT ck_gradebook_assessment_term CHECK (term IN ('TERM_1','TERM_2','TERM_3'))
);

CREATE UNIQUE INDEX uq_gradebook_assessment_client ON gradebook_assessments (client_id);
CREATE INDEX ix_gradebook_assessments_class ON gradebook_assessments (class_id, term);

CREATE TABLE gradebook_entries (
    id                uuid PRIMARY KEY,
    client_id         varchar(80) NOT NULL,
    class_id          uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    assessment_id     varchar(80) NOT NULL,
    teacher_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_name      varchar(160) NOT NULL,
    assessment_type   varchar(16) NOT NULL,
    assessment_title  varchar(200),
    raw_score         integer NOT NULL,
    max_score         integer NOT NULL,
    percentage        integer NOT NULL,
    cbc_strand_tag    varchar(128),
    teacher_note      text,
    graded_at         timestamp with time zone NOT NULL,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_gradebook_entry_client ON gradebook_entries (client_id);
CREATE UNIQUE INDEX uq_gradebook_entry ON gradebook_entries (class_id, assessment_id, student_id);
CREATE INDEX ix_gradebook_entries_class ON gradebook_entries (class_id);
CREATE INDEX ix_gradebook_entries_student ON gradebook_entries (student_id);

-- ===========================================================================
-- V32__homework_term
-- ===========================================================================

-- ============================================================
-- BrainboxApi V32: homework term tag
-- docs/ongoing/api_gradebook_changes.md section 2.2: the gradebook term filter
-- must cover homework, so homework carries an explicit term (month fallback for
-- legacy rows).
-- ============================================================

ALTER TABLE homework ADD COLUMN term varchar(16);

-- ===========================================================================
-- V33__homework_submission_idempotency
-- ===========================================================================

-- ============================================================
-- BrainboxApi V33: homework submission idempotency key
-- docs/ongoing/api_homework_changes.md: the client replays offline submits with a
-- stable clientSubmissionId; storing it lets a repeat be a no-op.
-- ============================================================

ALTER TABLE homework_submissions ADD COLUMN client_submission_id varchar(80);

-- ===========================================================================
-- V34__teacher_content
-- ===========================================================================

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

-- ===========================================================================
-- V35__teacher_documents
-- ===========================================================================

-- ============================================================
-- BrainboxApi V35: teacher documents (readable_files teacher columns)
-- Contract: BrainBox/docs/ongoing/api_content_changes.md
-- ============================================================

ALTER TABLE readable_files ADD COLUMN client_id varchar(80);
ALTER TABLE readable_files ADD COLUMN description text;
ALTER TABLE readable_files ADD COLUMN author_name varchar(160);
ALTER TABLE readable_files ADD COLUMN topic varchar(255);
-- The client document type (PDF/EPUB/PLAINTEXT) when it exceeds FileType.
ALTER TABLE readable_files ADD COLUMN doc_type varchar(16);

CREATE UNIQUE INDEX uq_readable_files_client ON readable_files (client_id);
CREATE INDEX ix_readable_files_creator ON readable_files (created_by, is_active);

-- ===========================================================================
-- V36__teacher_announcements
-- ===========================================================================

-- ============================================================
-- BrainboxApi V36: teacher announcements
-- Contract: BrainBox/docs/ongoing/api_announcement_changes.md (doc 04 section 6)
-- ============================================================

CREATE TABLE teacher_announcements (
    id                 uuid PRIMARY KEY,
    client_id          varchar(80) NOT NULL,
    teacher_id         uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id          uuid,
    title              varchar(200) NOT NULL,
    content            text NOT NULL,
    announcement_type  varchar(16) NOT NULL,
    audience           varchar(16) NOT NULL,
    target_class_ids   text,
    target_grade_levels text,
    target_student_ids text,
    is_priority        boolean NOT NULL DEFAULT FALSE,
    sent_at            timestamp with time zone NOT NULL,
    scheduled_at       timestamp with time zone,
    expires_at         timestamp with time zone,
    delivered_at       timestamp with time zone,
    created_at         timestamp with time zone NOT NULL DEFAULT now(),
    updated_at         timestamp with time zone NOT NULL DEFAULT now(),
    version            bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_announcement_type CHECK (announcement_type IN ('NOTICE','EVENT','EXAM','MEETING')),
    CONSTRAINT ck_announcement_audience CHECK (audience IN ('CLASS','GRADE','SCHOOL','TEACHER','PARENT'))
);

CREATE UNIQUE INDEX uq_teacher_announcement_client ON teacher_announcements (client_id);
CREATE INDEX ix_teacher_announcements_teacher ON teacher_announcements (teacher_id, sent_at);
CREATE INDEX ix_teacher_announcements_due ON teacher_announcements (delivered_at, scheduled_at);

CREATE TABLE announcement_views (
    id               uuid PRIMARY KEY,
    announcement_id  uuid NOT NULL REFERENCES teacher_announcements (id) ON DELETE CASCADE,
    student_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    viewed_at        timestamp with time zone,
    acknowledged_at  timestamp with time zone
);

CREATE UNIQUE INDEX uq_announcement_view ON announcement_views (announcement_id, student_id);

-- ===========================================================================
-- V37__teacher_exam_authoring
-- ===========================================================================

-- ============================================================
-- BrainboxApi V37: teacher digital exam authoring, review and remediation
-- Contract: BrainBox/docs/ongoing/api_exams_changes.md
-- The exams domain already owns the exam/question/submission tables; this
-- migration adds the authoring scope, the reusable question bank, the
-- per-question review marks and the class-wide remediation assignments.
-- ============================================================

ALTER TABLE exams ADD COLUMN client_id varchar(80);
ALTER TABLE exams ADD COLUMN class_id uuid;
ALTER TABLE exams ADD COLUMN grade_level integer;
ALTER TABLE exams ADD COLUMN total_points integer NOT NULL DEFAULT 0;
ALTER TABLE exams ADD COLUMN term varchar(16);
ALTER TABLE exams ADD COLUMN open_at timestamp with time zone;
ALTER TABLE exams ADD COLUMN close_at timestamp with time zone;

CREATE UNIQUE INDEX uq_exams_client ON exams (client_id);
CREATE INDEX ix_exams_author ON exams (created_by, created_at);

ALTER TABLE exam_questions ADD COLUMN client_id varchar(80);
ALTER TABLE exam_questions ADD COLUMN section_id varchar(80);
ALTER TABLE exam_questions ADD COLUMN cbc_strand_tag varchar(128);
ALTER TABLE exam_questions ADD COLUMN is_key_question boolean NOT NULL DEFAULT FALSE;
ALTER TABLE exam_questions ADD COLUMN requires_explanation boolean NOT NULL DEFAULT FALSE;
ALTER TABLE exam_questions ADD COLUMN is_from_bank boolean NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_exam_question_client ON exam_questions (exam_id, client_id);

CREATE TABLE exam_sections (
    id               uuid PRIMARY KEY,
    exam_id          uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    client_id        varchar(80) NOT NULL,
    title            varchar(255) NOT NULL,
    instructions     text,
    duration_minutes integer,
    sort_order       integer NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_exam_section_client ON exam_sections (exam_id, client_id);

CREATE TABLE teacher_question_bank (
    id              uuid PRIMARY KEY,
    client_id       varchar(80) NOT NULL,
    teacher_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id       uuid,
    text            text NOT NULL,
    q_type          varchar(32) NOT NULL,
    options         text,
    correct_answer  text,
    explanation     text,
    points          integer NOT NULL DEFAULT 1,
    difficulty      integer NOT NULL DEFAULT 3,
    matching_pairs  text,
    cbc_strand_tag  varchar(128),
    subject         varchar(128),
    grade_level     integer,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_question_bank_type CHECK (q_type IN ('MCQ','MULTI_SELECT','SHORT_ANSWER','ESSAY',
                                                      'TRUE_FALSE','MATCHING','FILL_BLANK','NUMBER_ENTRY')),
    CONSTRAINT ck_question_bank_difficulty CHECK (difficulty BETWEEN 1 AND 5)
);

CREATE UNIQUE INDEX uq_question_bank_client ON teacher_question_bank (client_id);
CREATE INDEX ix_question_bank_teacher ON teacher_question_bank (teacher_id);

CREATE TABLE exam_review_marks (
    id            uuid PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES exam_submissions (id) ON DELETE CASCADE,
    question_id   uuid NOT NULL REFERENCES exam_questions (id) ON DELETE CASCADE,
    mark          integer NOT NULL DEFAULT 0,
    reviewed_by   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reviewed_at   timestamp with time zone NOT NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_exam_review_mark ON exam_review_marks (submission_id, question_id);
CREATE INDEX ix_exam_review_marks_question ON exam_review_marks (question_id);

CREATE TABLE exam_remediations (
    id           uuid PRIMARY KEY,
    exam_id      uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    cbc_strand   varchar(128) NOT NULL,
    action       varchar(32) NOT NULL,
    assigned_by  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    assigned_at  timestamp with time zone NOT NULL,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_exam_remediation_action CHECK (action IN ('PRACTICE_EXERCISES','EXTRA_LESSON',
                                                            'LEARNING_LAB','MONITOR_ONLY'))
);

CREATE UNIQUE INDEX uq_exam_remediation_strand ON exam_remediations (exam_id, cbc_strand);

-- ===========================================================================
-- V38__teacher_timetable
-- ===========================================================================

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

-- ===========================================================================
-- V39__content_post_status
-- ===========================================================================

-- ============================================================
-- BrainboxApi V39: content post lifecycle (schedule + archive)
-- docs/ongoing/api_content_changes.md: a post carries PUBLISHED | SCHEDULED |
-- ARCHIVED and a scheduled post is held until its publish instant.
-- ============================================================

ALTER TABLE learning_posts ADD COLUMN status varchar(16) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE learning_posts ADD COLUMN publish_at timestamp with time zone;
CREATE INDEX ix_learning_posts_status ON learning_posts (status, publish_at);

-- ===========================================================================
-- V40__school_announcements
-- ===========================================================================

-- ============================================================
-- BrainboxApi V40: school (admin) announcements
-- docs/ongoing/open_gaps.md ANN-1: the admin studio is offline-first, so the
-- CRUD endpoints are the server source of truth for school-wide notices.
-- ============================================================

CREATE TABLE school_announcements (
    id                     uuid PRIMARY KEY,
    school_id              uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    title                  varchar(200) NOT NULL,
    body                   text NOT NULL,
    audience               varchar(120) NOT NULL,
    posted_by              uuid REFERENCES users (id),
    posted_by_name         varchar(160),
    posted_at              timestamp with time zone NOT NULL,
    scheduled_meeting_date timestamp with time zone,
    meeting_title          varchar(200),
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    updated_at             timestamp with time zone NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_school_announcements_school ON school_announcements (school_id, posted_at);

-- ===========================================================================
-- V41__live_class_hosting
-- ===========================================================================

-- ============================================================
-- BrainboxApi V41: teacher live-class hosting surface
-- docs/ongoing/api_live_class_changes.md: teacher authoring settings, the host
-- roster, and the replay-safe chat transcript. Every teacher write carries a
-- client id so the LiveSyncWorker outbox can replay it idempotently.
-- ============================================================

ALTER TABLE live_classes ADD COLUMN client_id varchar(80);
ALTER TABLE live_classes ADD COLUMN visibility varchar(32) NOT NULL DEFAULT 'CLASS_ONLY';
ALTER TABLE live_classes ADD COLUMN auto_record boolean NOT NULL DEFAULT TRUE;
ALTER TABLE live_classes ADD COLUMN mute_on_join boolean NOT NULL DEFAULT TRUE;
ALTER TABLE live_classes ADD COLUMN waiting_room boolean NOT NULL DEFAULT FALSE;
ALTER TABLE live_classes ADD COLUMN allow_chat boolean NOT NULL DEFAULT TRUE;
ALTER TABLE live_classes ADD COLUMN allow_q_and_a boolean NOT NULL DEFAULT TRUE;
ALTER TABLE live_classes ADD COLUMN participant_ids text;
ALTER TABLE live_classes ADD COLUMN material_ids text;
ALTER TABLE live_classes ADD COLUMN analytics_id varchar(80);

CREATE UNIQUE INDEX uq_live_class_client ON live_classes (client_id);

CREATE TABLE live_class_participants (
    id         uuid PRIMARY KEY,
    class_id   uuid NOT NULL REFERENCES live_classes (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name  varchar(160) NOT NULL,
    role       varchar(16) NOT NULL DEFAULT 'STUDENT'
               CHECK (role IN ('STUDENT','TEACHER','INSTRUCTOR','SUPPORT','COHOST')),
    is_muted   boolean NOT NULL DEFAULT FALSE,
    join_time  timestamp with time zone NOT NULL,
    is_removed boolean NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_live_participant ON live_class_participants (class_id, user_id);

CREATE TABLE live_class_messages (
    id        uuid PRIMARY KEY,
    client_id varchar(80) NOT NULL,
    class_id  uuid NOT NULL REFERENCES live_classes (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name varchar(160) NOT NULL,
    user_role varchar(16) NOT NULL DEFAULT 'STUDENT',
    message   text NOT NULL,
    sent_at   timestamp with time zone NOT NULL,
    is_pinned boolean NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_live_message_client ON live_class_messages (client_id);
CREATE INDEX ix_live_messages_class ON live_class_messages (class_id, sent_at);

-- ===========================================================================
-- V42__teacher_feedback
-- ===========================================================================

-- ============================================================
-- BrainboxApi V42: teacher feedback (doc 04 section 7)
-- Feedback writes and templates are offline-first on the client (built-in
-- teacher mutations outbox), so both use a unique client_id for replay.
-- ============================================================

CREATE TABLE teacher_feedback_templates (
    id         uuid PRIMARY KEY,
    client_id  varchar(80) NOT NULL,
    teacher_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      varchar(200) NOT NULL,
    content    text NOT NULL,
    category   varchar(64) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_feedback_template_client ON teacher_feedback_templates (client_id);
CREATE INDEX ix_feedback_templates_teacher ON teacher_feedback_templates (teacher_id);

CREATE TABLE teacher_feedback (
    id                  uuid PRIMARY KEY,
    client_id           varchar(80) NOT NULL,
    teacher_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    submission_id       varchar(120),
    text_feedback       text,
    voice_feedback_url  varchar(512),
    photo_feedback_urls text,
    rubric_scores       text,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_teacher_feedback_client ON teacher_feedback (client_id);
CREATE INDEX ix_teacher_feedback_teacher ON teacher_feedback (teacher_id, created_at);
CREATE INDEX ix_teacher_feedback_student ON teacher_feedback (student_id, created_at);

-- ===========================================================================
-- V43__learning_contracts
-- ===========================================================================

-- ============================================================
-- BrainboxApi V43: teacher learning contracts (doc 04 section 11)
-- Contract CRUD + per-commitment completion, reminder fan-out and the canonical
-- template catalogue. Client ids make the offline outbox replay idempotent.
-- ============================================================

CREATE TABLE learning_contracts (
    id           uuid PRIMARY KEY,
    client_id    varchar(80) NOT NULL,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    child_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    term         varchar(32) NOT NULL,
    status       varchar(16) NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','COMPLETED','EXPIRED')),
    start_date   timestamp with time zone NOT NULL,
    end_date     timestamp with time zone NOT NULL,
    last_updated timestamp with time zone NOT NULL,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_learning_contract_client ON learning_contracts (client_id);
CREATE INDEX ix_learning_contracts_teacher ON learning_contracts (teacher_id);
CREATE INDEX ix_learning_contracts_child ON learning_contracts (child_id);

CREATE TABLE learning_contract_commitments (
    id              uuid PRIMARY KEY,
    client_id       varchar(80) NOT NULL,
    contract_id     uuid NOT NULL REFERENCES learning_contracts (id) ON DELETE CASCADE,
    party           varchar(16) NOT NULL,
    text            text NOT NULL,
    is_completed    boolean NOT NULL DEFAULT FALSE,
    due_date        timestamp with time zone,
    notes           text,
    completion_date timestamp with time zone,
    sort_order      integer NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_contract_commitment_client ON learning_contract_commitments (contract_id, client_id);
CREATE INDEX ix_contract_commitments_contract ON learning_contract_commitments (contract_id, sort_order);

CREATE TABLE contract_templates (
    id                  uuid PRIMARY KEY,
    title               varchar(200) NOT NULL,
    description         text NOT NULL,
    category            varchar(64) NOT NULL,
    default_commitments text,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0
);

INSERT INTO contract_templates (id, title, description, category, default_commitments) VALUES
('11111111-1111-4111-8111-111111111101', 'Academic Improvement Plan',
 'A focused plan to lift a learner''s grades over the term.', 'ACADEMIC',
 '[{"id":"c1","party":"STUDENT","text":"Attend every class and take notes"},{"id":"c2","party":"PARENT","text":"Check homework and revision weekly"},{"id":"c3","party":"TEACHER","text":"Share a progress note every two weeks"}]'),
('11111111-1111-4111-8111-111111111102', 'Assignment Completion Contract',
 'Ensures homework is set, done and returned on time.', 'HOMEWORK',
 '[{"id":"c1","party":"TEACHER","text":"Post assignments by Monday"},{"id":"c2","party":"STUDENT","text":"Submit all assignments before the deadline"},{"id":"c3","party":"PARENT","text":"Confirm submissions are complete"}]'),
('11111111-1111-4111-8111-111111111103', 'Engagement & Behaviour Contract',
 'Improves class participation and positive behaviour.', 'ENGAGEMENT',
 '[{"id":"c1","party":"STUDENT","text":"Participate in at least one class discussion a day"},{"id":"c2","party":"PARENT","text":"Attend a monthly check-in with the teacher"},{"id":"c3","party":"TEACHER","text":"Recognise good participation weekly"}]');

-- ===========================================================================
-- V44__teacher_settings
-- ===========================================================================

-- ============================================================
-- BrainboxApi V44: teacher settings (doc 04 teacher profile/settings)
-- The teacher account itself stays in users; this table holds the app's
-- TeacherSettings fields (preferences, notification switches, weighting).
-- ============================================================

CREATE TABLE teacher_settings (
    id                          uuid PRIMARY KEY,
    teacher_id                  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subjects_taught             text,
    tsc_number                  varchar(64),
    default_grade_level         integer,
    language_preference         varchar(16) NOT NULL DEFAULT 'en',
    theme_preference            varchar(16) NOT NULL DEFAULT 'dark',
    auto_attendance             boolean NOT NULL DEFAULT TRUE,
    notifications_enabled       boolean NOT NULL DEFAULT TRUE,
    default_grade_weighting     text,
    assignment_submission_alerts boolean NOT NULL DEFAULT TRUE,
    new_exam_publish            boolean NOT NULL DEFAULT TRUE,
    parent_messages             boolean NOT NULL DEFAULT TRUE,
    staff_bulletin              boolean NOT NULL DEFAULT TRUE,
    email_notifications         boolean NOT NULL DEFAULT FALSE,
    created_at                  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at                  timestamp with time zone NOT NULL DEFAULT now(),
    version                     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_teacher_settings_teacher ON teacher_settings (teacher_id);

-- ===========================================================================
-- V45__cbc_strands_ratings
-- ===========================================================================

-- ============================================================
-- BrainboxApi V45: CBC strands and teacher ratings
-- doc 04 CBC analytics: the curriculum map is the server catalogue and the
-- teacher rating endpoint upserts one rating per (student, strand, term).
-- ============================================================

CREATE TABLE cbc_strands (
    id          uuid PRIMARY KEY,
    code        varchar(32) NOT NULL,
    name        varchar(160) NOT NULL,
    descriptor  text NOT NULL,
    grade_level varchar(32) NOT NULL DEFAULT 'ALL',
    subject     varchar(64) NOT NULL,
    sort_order  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_cbc_strand ON cbc_strands (code, grade_level);

CREATE TABLE cbc_ratings (
    id          uuid PRIMARY KEY,
    student_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    strand_code varchar(32) NOT NULL,
    term        varchar(32) NOT NULL,
    rating      varchar(16) NOT NULL
                CHECK (rating IN ('EXCEEDING','MEETING','APPROACHING','BELOW')),
    evidence    varchar(512),
    comments    text,
    rated_at    timestamp with time zone NOT NULL,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_cbc_rating ON cbc_ratings (student_id, strand_code, term);
CREATE INDEX ix_cbc_ratings_student ON cbc_ratings (student_id, term);

INSERT INTO cbc_strands (id, code, name, descriptor, grade_level, subject, sort_order) VALUES
('22222222-2222-4222-8222-222222220001', 'MAT-NUM', 'Numbers', 'Count, order, add, subtract, multiply and divide whole numbers, fractions and decimals.', 'ALL', 'Mathematics', 1),
('22222222-2222-4222-8222-222222220002', 'MAT-MEAS', 'Measurement', 'Measure length, mass, capacity, time and money, and solve measurement problems.', 'ALL', 'Mathematics', 2),
('22222222-2222-4222-8222-222222220003', 'MAT-GEO', 'Geometry', 'Identify, describe and construct 2D and 3D shapes and angles.', 'ALL', 'Mathematics', 3),
('22222222-2222-4222-8222-222222220004', 'MAT-DATA', 'Data Handling', 'Collect, represent, interpret and use data to solve problems.', 'ALL', 'Mathematics', 4),
('22222222-2222-4222-8222-222222220005', 'ENG-LISTEN', 'Listening and Speaking', 'Listen, respond and speak confidently for different purposes.', 'ALL', 'English', 5),
('22222222-2222-4222-8222-222222220006', 'ENG-READ', 'Reading', 'Read fluently and comprehend a range of texts.', 'ALL', 'English', 6),
('22222222-2222-4222-8222-222222220007', 'ENG-WRITE', 'Writing', 'Write clear, well-organised texts for different audiences and purposes.', 'ALL', 'English', 7),
('22222222-2222-4222-8222-222222220008', 'SCI-LIVING', 'Living Things', 'Observe, classify and explain living things and their environment.', 'ALL', 'Integrated Science', 8),
('22222222-2222-4222-8222-222222220009', 'SCI-MATTER', 'Matter', 'Investigate the properties, states and changes of matter.', 'ALL', 'Integrated Science', 9),
('22222222-2222-4222-8222-222222220010', 'SCI-ENERGY', 'Energy', 'Investigate energy, forces and their applications.', 'ALL', 'Integrated Science', 10),
('22222222-2222-4222-8222-222222220011', 'SCI-ENV', 'Environment', 'Conserve and manage the environment sustainably.', 'ALL', 'Integrated Science', 11),
('22222222-2222-4222-8222-222222220012', 'KIS-LUGHA', 'Lugha na Matumizi', 'Tumia Kiswahili kwa mawasiliano sahihi.', 'ALL', 'Kiswahili', 12),
('22222222-2222-4222-8222-222222220013', 'KIS-KUSOMA', 'Kusoma na Kuelewa', 'Soma na uelewe matini mbalimbali.', 'ALL', 'Kiswahili', 13),
('22222222-2222-4222-8222-222222220014', 'SST-CITIZEN', 'Citizenship', 'Understand rights, responsibilities and governance.', 'ALL', 'Social Studies', 14),
('22222222-2222-4222-8222-222222220015', 'SST-GEOG', 'Geography', 'Locate and describe physical and human features.', 'ALL', 'Social Studies', 15);

-- ===========================================================================
-- V46__conferences
-- ===========================================================================

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

-- ===========================================================================
-- V47__class_group_poll_client_id
-- ===========================================================================

-- ============================================================
-- BrainboxApi V47: class-group poll client id
-- docs/ongoing/backend_conformance.md: an offline poll-create replay carries a
-- stable clientPollId, so the server upserts by it instead of duplicating.
-- ============================================================

ALTER TABLE class_group_polls ADD COLUMN client_id varchar(80);
CREATE UNIQUE INDEX uq_class_group_poll_client ON class_group_polls (client_id);

-- ===========================================================================
-- V48__reports
-- ===========================================================================

-- ============================================================
-- BrainboxApi V48: reporting hub + school branding config
-- docs/ongoing/api_reports_changes.md (async generation, branding,
-- authorization, history, schedules, quota).
--
-- school_configs is the server source of the SchoolConfig branding the
-- client applies on-device (PdfGenerator.SchoolReportBranding); it is exposed
-- to ICT admins at admin/schools/{id}/config and read by report rendering.
-- report_jobs backs both the async job poll and the paged export history.
-- report_downloads is the server-authoritative weekly export quota ledger.
-- ============================================================

CREATE TABLE school_configs (
    school_id         uuid PRIMARY KEY REFERENCES schools (id) ON DELETE CASCADE,
    school_name       varchar(120) NOT NULL,
    motto             varchar(160),
    logo_url          varchar(512),
    primary_color     varchar(16),
    address           varchar(255),
    phone             varchar(64),
    email             varchar(200),
    watermark_text    varchar(120),
    academic_calendar text,
    cbc_strands       text,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE TABLE report_jobs (
    id                uuid PRIMARY KEY,
    request_id        varchar(80)  NOT NULL,
    owner_id          uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id         uuid         REFERENCES schools (id) ON DELETE SET NULL,
    report_type       varchar(64)  NOT NULL,
    title             varchar(200),
    status            varchar(24)  NOT NULL,
    progress          integer      NOT NULL DEFAULT 0,
    message           varchar(500),
    failure_reason    varchar(500),
    class_id          varchar(80),
    exam_id           varchar(80),
    term              varchar(64),
    report_year       integer,
    file_name         varchar(255),
    file_size         bigint       NOT NULL DEFAULT 0,
    storage_name      varchar(255),
    poll_after_millis bigint,
    cancel_requested  boolean      NOT NULL DEFAULT false,
    payload_json      text,
    completed_at      timestamp with time zone,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_report_jobs_request UNIQUE (owner_id, request_id)
);

CREATE INDEX ix_report_jobs_owner_created ON report_jobs (owner_id, created_at);

CREATE TABLE report_schedules (
    id          uuid PRIMARY KEY,
    client_id   varchar(80) NOT NULL,
    owner_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id   uuid        REFERENCES schools (id) ON DELETE SET NULL,
    title       varchar(200) NOT NULL,
    report_type varchar(64)  NOT NULL,
    class_id    varchar(80),
    term        varchar(64),
    frequency   varchar(24)  NOT NULL,
    destination varchar(120) NOT NULL,
    enabled     boolean      NOT NULL DEFAULT true,
    next_run_at timestamp with time zone NOT NULL,
    last_run_at timestamp with time zone,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_report_schedules_client UNIQUE (owner_id, client_id)
);

CREATE INDEX ix_report_schedules_owner ON report_schedules (owner_id, next_run_at);
CREATE INDEX ix_report_schedules_due ON report_schedules (enabled, next_run_at);

CREATE TABLE report_downloads (
    id            uuid PRIMARY KEY,
    owner_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id        uuid NOT NULL REFERENCES report_jobs (id) ON DELETE CASCADE,
    downloaded_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_report_downloads_owner ON report_downloads (owner_id, downloaded_at);

-- ===========================================================================
-- V49__teacher_lifecycle
-- ===========================================================================

-- ============================================================
-- BrainboxApi V49: account verification state + CTC freeze
-- docs/ongoing/api_teacher_roster_changes.md (teacher auth lifecycle).
-- verification_status carries the client AccountStatus state machine
-- (PENDING_VERIFICATION | VERIFIED | REJECTED | FROZEN); teacher_codes.frozen
-- lets a teacher freeze their Class Teacher Code so students cannot join.
-- ============================================================

ALTER TABLE users ADD COLUMN verification_status varchar(24) NOT NULL DEFAULT 'VERIFIED';
UPDATE users SET verification_status = 'PENDING_VERIFICATION' WHERE is_verified = false;
ALTER TABLE teacher_codes ADD COLUMN frozen boolean NOT NULL DEFAULT false;

-- ===========================================================================
-- V50__account_security
-- ===========================================================================

-- ============================================================
-- BrainboxApi V50: account security + school registration queue
-- POST auth/change-password (self) and POST auth/register-school
-- (moderation queue; the school is created only on admin approval).
-- ============================================================

CREATE TABLE school_registration_requests (
    id           uuid PRIMARY KEY,
    request_id   varchar(80) NOT NULL,
    school_name  varchar(160) NOT NULL,
    address      varchar(255),
    submitted_by uuid REFERENCES users (id) ON DELETE SET NULL,
    submitted_at timestamp with time zone NOT NULL DEFAULT now(),
    status       varchar(16) NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    reviewed_by  uuid REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at  timestamp with time zone,
    review_note  varchar(500),
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_school_registration_request UNIQUE (request_id)
);

CREATE INDEX ix_school_registration_status ON school_registration_requests (status, created_at);

-- ===========================================================================
-- V51__conference_approval_gate
-- ===========================================================================

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

-- ===========================================================================
-- V52__admin_settings_audit
-- ===========================================================================

-- ============================================================
-- BrainboxApi V52: admin school settings, audit trail and backups
-- docs/ongoing/api_admin_changes.md (school system settings, audit logs,
-- explicit backup). Audit logs are server-owned; the client caches this read.
-- ============================================================

CREATE TABLE school_system_settings (
    school_id         uuid PRIMARY KEY REFERENCES schools (id) ON DELETE CASCADE,
    maintenance_mode  boolean NOT NULL DEFAULT false,
    registration_open boolean NOT NULL DEFAULT true,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE TABLE audit_logs (
    id         uuid PRIMARY KEY,
    school_id  uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    actor_id   uuid REFERENCES users (id) ON DELETE SET NULL,
    actor_name varchar(160) NOT NULL,
    action     varchar(200) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_audit_logs_school ON audit_logs (school_id, created_at);

CREATE TABLE school_backups (
    id           uuid PRIMARY KEY,
    school_id    uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    requested_by uuid REFERENCES users (id) ON DELETE SET NULL,
    status       varchar(16) NOT NULL DEFAULT 'READY'
                 CHECK (status IN ('READY','FAILED')),
    size_bytes   bigint NOT NULL DEFAULT 0,
    payload      text,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_school_backups_school ON school_backups (school_id, created_at);

-- ===========================================================================
-- V53__admin_caveats
-- ===========================================================================

-- ============================================================
-- BrainboxApi V53: admin analytics trends, retrievable backups and an
-- audit trail that can be paged and pruned.
-- ============================================================

ALTER TABLE school_backups ADD COLUMN file_name varchar(255);
ALTER TABLE school_backups ADD COLUMN sha256 varchar(64);

CREATE TABLE school_performance_snapshots (
    id                  uuid PRIMARY KEY,
    school_id           uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    term                varchar(16) NOT NULL,
    snapshot_year       integer NOT NULL,
    overall_performance double precision NOT NULL DEFAULT 0,
    class_averages      text,
    generated_at        timestamp with time zone NOT NULL DEFAULT now(),
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_school_snapshot UNIQUE (school_id, term, snapshot_year)
);

-- ===========================================================================
-- V54__password_reset
-- ===========================================================================

-- ============================================================
-- BrainboxApi V54: password recovery (docs/ongoing/api_auth_changes.md)
-- One row per reset challenge: a hashed one-time code, then a short-lived
-- single-use reset token. Delivery is out of band (SMS/email); the OTP is never
-- returned by the API.
-- ============================================================

CREATE TABLE password_reset_challenges (
    id                      uuid PRIMARY KEY,
    identifier              varchar(160) NOT NULL,
    user_id                 uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    otp_hash                varchar(64) NOT NULL,
    expires_at              timestamp with time zone NOT NULL,
    attempts                integer NOT NULL DEFAULT 0,
    verified                boolean NOT NULL DEFAULT false,
    reset_token_hash        varchar(64),
    reset_token_expires_at  timestamp with time zone,
    consumed                boolean NOT NULL DEFAULT false,
    created_at              timestamp with time zone NOT NULL DEFAULT now(),
    updated_at              timestamp with time zone NOT NULL DEFAULT now(),
    version                 bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_password_reset_identifier ON password_reset_challenges (identifier, consumed, created_at);
CREATE INDEX ix_password_reset_token ON password_reset_challenges (reset_token_hash);

-- ===========================================================================
-- V55__recommendations
-- ===========================================================================

-- ============================================================
-- BrainboxApi V55: recommendation interaction telemetry
-- docs/ongoing/api_recommendations_changes.md. The learner rail treats the
-- server as the source of truth; these rows feed collaborative and trending
-- signals. De-duplicated per (user, post, timestamp) for best-effort replay.
-- ============================================================

CREATE TABLE recommendation_interactions (
    id                 uuid PRIMARY KEY,
    user_id            uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id            uuid NOT NULL REFERENCES learning_posts (id) ON DELETE CASCADE,
    interaction_type   varchar(32) NOT NULL,
    time_spent_seconds integer,
    interaction_score  double precision NOT NULL DEFAULT 0,
    occurred_at        timestamp with time zone NOT NULL,
    school_id          uuid REFERENCES schools (id) ON DELETE SET NULL,
    created_at         timestamp with time zone NOT NULL DEFAULT now(),
    updated_at         timestamp with time zone NOT NULL DEFAULT now(),
    version            bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_recommendation_interaction UNIQUE (user_id, post_id, occurred_at)
);

CREATE INDEX ix_recommendation_interactions_school ON recommendation_interactions (school_id, occurred_at);
CREATE INDEX ix_recommendation_interactions_user ON recommendation_interactions (user_id, post_id);

-- ===========================================================================
-- V56__school_rooms
-- ===========================================================================

-- ============================================================
-- BrainboxApi V56: school room catalogue (TT-3)
-- SchoolConfig.rooms is the server-owned room list the client room picker
-- reads. Stored as a JSON list like academic_calendar/cbc_strands.
-- ============================================================

ALTER TABLE school_configs ADD COLUMN rooms text;

-- ===========================================================================
-- V57__device_tokens
-- ===========================================================================

-- ============================================================
-- BrainboxApi V57: FCM device tokens (LC-1)
-- docs/ongoing/api_push_changes.md. One row per registration token; a token
-- may move between accounts, so the latest registration wins (unique token).
-- ============================================================

CREATE TABLE device_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token       varchar(512) NOT NULL,
    platform    varchar(32) NOT NULL DEFAULT 'ANDROID',
    app_version varchar(32),
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_device_token UNIQUE (token)
);

CREATE INDEX ix_device_tokens_user ON device_tokens (user_id);

-- ===========================================================================
-- V58__traditional_exam_client_ids
-- ===========================================================================

-- ============================================================
-- V58: traditional exam client-assigned identifiers
--
-- The Android client generates deterministic exam ids of the form
-- TRAD_<grade>_<OPENER|MID|END>_<year>_T<term> before it ever reaches the
-- server (core/calendar/ExamCalendarGenerator.kt). The server keeps its own
-- UUID primary key but stores that client id so exams, marks, confirmations,
-- analytics and edit requests can round-trip under the id the client knows.
--
-- Contract: BrainBox/docs/ongoing/traditional_exams_audit.md (TE-2/TE-3/TE-6).
-- ============================================================

ALTER TABLE traditional_exams ADD COLUMN client_exam_id varchar(128);

-- One exam per client id per school. Nullable client ids (legacy rows created
-- before this migration) stay unconstrained because NULLs are distinct.
CREATE UNIQUE INDEX uq_traditional_exams_client_id ON traditional_exams (school_id, client_exam_id);

-- ===========================================================================
-- V59__leaderboard_seasons
-- ===========================================================================

-- ============================================================
-- V59: admin leaderboard seasons (Phase 5 leaderboard management)
-- Contract: docs/backend_contracts/08_ADMIN_PANEL_AND_SCHOOL_MANAGEMENT.md §7
--
-- XP is event-sourced (V20 xp_events), so every timeframe leaderboard is computed
-- on the fly. A reset marks a new season boundary for one timeframe instead of
-- deleting award history; the admin board then only counts XP earned after it.
-- ============================================================

CREATE TABLE leaderboard_seasons (
    timeframe  varchar(16) PRIMARY KEY,
    started_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_leaderboard_season_timeframe CHECK (timeframe IN ('WEEKLY','MONTHLY','TERM','ALL'))
);

-- ===========================================================================
-- V60__doubt_bookmarks
-- ===========================================================================

-- ============================================================
-- BrainboxApi V60: doubt question bookmarks
-- Contract: docs/backend_contracts/05_... §3
-- One bookmark row per (user, question); the unique index makes the toggle
-- idempotent under concurrent taps.
-- ============================================================

CREATE TABLE doubt_bookmarks (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    question_id uuid NOT NULL REFERENCES doubt_questions (id) ON DELETE CASCADE,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_doubt_bookmark ON doubt_bookmarks (user_id, question_id);

-- ===========================================================================
-- V61__content_concepts
-- ===========================================================================

-- ============================================================
-- BrainboxApi V61: content concept layer + generation cache
-- Phase 7.1: the concept catalogue, its curriculum map, generated/uploaded
-- content units (with steps and questions) and the generation job queue.
-- H2 (PostgreSQL mode) and PostgreSQL compatible: no partial indexes and no
-- now()+interval arithmetic; UUID ids and per-row timestamps/version come from
-- BaseEntity (created_at, updated_at, version).
-- ============================================================

CREATE TABLE concepts (
    id          uuid PRIMARY KEY,
    code        varchar(64) NOT NULL,
    name        varchar(200) NOT NULL,
    description text,
    subject     varchar(64) NOT NULL,
    parent_id   uuid REFERENCES concepts (id) ON DELETE SET NULL,
    sort_order  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_concepts_code ON concepts (code);

CREATE TABLE curriculum_map (
    id               uuid PRIMARY KEY,
    concept_id       uuid NOT NULL REFERENCES concepts (id) ON DELETE CASCADE,
    country_code     varchar(8) NOT NULL,
    curriculum       varchar(32) NOT NULL,
    grade_level      varchar(32) NOT NULL,
    strand_code      varchar(64),
    strand_name      varchar(160),
    substrand_code   varchar(64),
    substrand_name   varchar(160),
    learning_outcome text,
    sort_order       integer NOT NULL DEFAULT 0,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_curriculum_map_lookup
    ON curriculum_map (country_code, curriculum, grade_level, strand_code, substrand_code);

CREATE TABLE content_units (
    id               uuid PRIMARY KEY,
    generation_key   varchar(256) NOT NULL,
    task_type        varchar(32) NOT NULL,
    concept_id       uuid REFERENCES concepts (id) ON DELETE SET NULL,
    subject          varchar(64) NOT NULL,
    grade_level      varchar(32) NOT NULL,
    language         varchar(8) NOT NULL DEFAULT 'en',
    standard_version varchar(16) NOT NULL DEFAULT 'v1',
    schema_version   varchar(16) NOT NULL DEFAULT 'v1',
    prompt_version   varchar(32),
    body             text,
    provenance       varchar(16) NOT NULL DEFAULT 'GENERATED'
                     CHECK (provenance IN ('GENERATED','UPLOADED')),
    author_name      varchar(160),
    source_urls      text,
    license          varchar(64),
    model            varchar(64),
    tokens           integer NOT NULL DEFAULT 0,
    confidence       double precision,
    review_state     varchar(16) NOT NULL DEFAULT 'UNREVIEWED'
                     CHECK (review_state IN ('UNREVIEWED','REVIEWED','REJECTED')),
    status           varchar(16) NOT NULL DEFAULT 'DRAFT',
    published_at     timestamp with time zone,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_content_units_generation_key ON content_units (generation_key);
CREATE INDEX ix_content_units_lookup
    ON content_units (concept_id, grade_level, task_type, review_state);

CREATE TABLE content_unit_steps (
    id          uuid PRIMARY KEY,
    unit_id     uuid NOT NULL REFERENCES content_units (id) ON DELETE CASCADE,
    order_index integer NOT NULL DEFAULT 0,
    title       varchar(200),
    body        text,
    figure_svg  text,
    figure_url  varchar(512),
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_content_unit_steps_order ON content_unit_steps (unit_id, order_index);

CREATE TABLE content_unit_questions (
    id             uuid PRIMARY KEY,
    unit_id        uuid NOT NULL REFERENCES content_units (id) ON DELETE CASCADE,
    step_id        uuid REFERENCES content_unit_steps (id) ON DELETE SET NULL,
    order_index    integer NOT NULL DEFAULT 0,
    q_type         varchar(24) NOT NULL,
    text           text NOT NULL,
    options        text,
    correct_answer text,
    explanation    text,
    points         integer NOT NULL DEFAULT 1,
    difficulty     integer NOT NULL DEFAULT 3,
    matching_pairs text,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_content_unit_questions_order ON content_unit_questions (unit_id, order_index);

CREATE TABLE generation_jobs (
    id             uuid PRIMARY KEY,
    generation_key varchar(256) NOT NULL,
    task_type      varchar(32) NOT NULL,
    concept_id     uuid REFERENCES concepts (id) ON DELETE SET NULL,
    grade_level    varchar(32) NOT NULL,
    status         varchar(16) NOT NULL DEFAULT 'QUEUED',
    attempts       integer NOT NULL DEFAULT 0,
    last_error     text,
    run_id         uuid,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_generation_jobs_status ON generation_jobs (status, created_at);

-- ===========================================================================
-- V62__content_capture
-- ===========================================================================

-- ============================================================
-- BrainboxApi V62: content capture tables + generation runner
-- Phase 7.2: cache-first content router capture rows. The router is the only
-- writer of these tables; prompts are versioned so eval scores and feedback can
-- be attributed later. H2 (PostgreSQL mode) and PostgreSQL compatible: UUID ids
-- and per-row timestamps/version come from BaseEntity.
--
-- NOTE on prompt_versions: the semantic prompt version is stored in
-- prompt_version (varchar 32) rather than "version" because BaseEntity already
-- owns the optimistic-lock "version" bigint column. The task's "version
-- varchar(32)" and "version bigint DEFAULT 0" cannot coexist as one column name;
-- this keeps the required optimistic-lock schema uniform across tables.
-- ============================================================

CREATE TABLE prompt_versions (
    id             uuid PRIMARY KEY,
    prompt_key     varchar(128) NOT NULL,
    prompt_version varchar(32) NOT NULL,
    body           text NOT NULL,
    eval_score     double precision,
    is_active      boolean NOT NULL DEFAULT FALSE,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_prompt_versions_key_version ON prompt_versions (prompt_key, prompt_version);
CREATE INDEX ix_prompt_versions_active ON prompt_versions (prompt_key, is_active);

CREATE TABLE agent_runs (
    id             uuid PRIMARY KEY,
    job_id         uuid REFERENCES generation_jobs (id) ON DELETE CASCADE,
    generation_key varchar(256) NOT NULL,
    prompt_version varchar(32),
    model          varchar(64),
    iterations     integer NOT NULL DEFAULT 0,
    confidence     double precision,
    status         varchar(16) NOT NULL DEFAULT 'RUNNING',
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_agent_runs_generation_key ON agent_runs (generation_key);

CREATE TABLE model_calls (
    id                uuid PRIMARY KEY,
    agent_run_id      uuid NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    provider          varchar(32) NOT NULL,
    model             varchar(64),
    prompt_tokens     integer NOT NULL DEFAULT 0,
    completion_tokens integer NOT NULL DEFAULT 0,
    cost_micros       bigint NOT NULL DEFAULT 0,
    latency_ms        bigint NOT NULL DEFAULT 0,
    success           boolean NOT NULL DEFAULT TRUE,
    error             text,
    created_at        timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_model_calls_agent_run ON model_calls (agent_run_id);

CREATE TABLE tool_calls (
    id            uuid PRIMARY KEY,
    agent_run_id  uuid NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    tool_name     varchar(64) NOT NULL,
    success       boolean NOT NULL DEFAULT TRUE,
    latency_ms    bigint NOT NULL DEFAULT 0,
    request_json  text,
    response_json text,
    created_at    timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_tool_calls_agent_run ON tool_calls (agent_run_id);

-- ===========================================================================
-- V63__content_projection
-- ===========================================================================

-- ============================================================
-- BrainboxApi V63: content projection into the client-facing tables
-- Phase 7.3: the generated/uploaded content_units cache is projected onto the
-- tables the client already reads. A book-like unit (NOTES/BOOK/QUIZ/FLASHCARDS)
-- becomes a learning_posts row plus learning_content blocks; a CHUNK becomes a
-- readable_files row with an inline body. The shared concept layer is seeded from
-- the Kenya CBC strand catalogue (V45) so one concept can be re-localised later.
--
-- Deterministic and idempotent-safe: the cbc_strands UUID is reused as the
-- concepts.id and the curriculum_map.id (different tables), and each seed insert
-- is guarded by NOT EXISTS so a re-run on a fresh database cannot duplicate rows.
-- H2 (PostgreSQL mode) and PostgreSQL compatible.
-- ============================================================

ALTER TABLE content_units ADD COLUMN title varchar(255);
ALTER TABLE readable_files ADD COLUMN body text;

-- The CBC strand catalogue is the first national mapping of the shared concept
-- layer: one concept per strand, keyed by the strand's stable UUID and coded
-- 'CBC-<strand code>'.
INSERT INTO concepts (id, code, name, description, subject, parent_id, sort_order)
SELECT s.id, 'CBC-' || s.code, s.name, s.descriptor, s.subject, NULL, s.sort_order
FROM cbc_strands s
WHERE NOT EXISTS (SELECT 1 FROM concepts c WHERE c.id = s.id);

-- Each seeded concept maps back to its Kenya CBC strand. The same UUID is reused
-- because the two tables are independent.
INSERT INTO curriculum_map (
    id, concept_id, country_code, curriculum, grade_level,
    strand_code, strand_name, substrand_code, substrand_name, learning_outcome, sort_order
)
SELECT s.id, s.id, 'KE', 'CBC', s.grade_level, s.code, s.name, NULL, NULL, s.descriptor, s.sort_order
FROM cbc_strands s
WHERE NOT EXISTS (SELECT 1 FROM curriculum_map m WHERE m.id = s.id);

-- ===========================================================================
-- V64__content_moderation
-- ===========================================================================

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

-- ===========================================================================
-- V65__content_moderation_auto
-- ===========================================================================

-- ============================================================
-- BrainboxApi V65: confidence auto-approval on moderation outcomes
-- Phase 7.4b. A machine decision must never be mistakable for a human one, so the
-- outcome records auto_approved plus the validator confidence score and the
-- resolving reviewer (null for an auto-approval). content_feedback gains provider so
-- the preference dataset keeps the full generation identity.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible. confidence_score is double
-- precision rather than numeric(5,4) so it maps to the entity Double exactly and
-- passes ddl-auto: validate on both engines.
-- ============================================================

ALTER TABLE moderation_outcomes ADD COLUMN reviewer_id uuid REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE moderation_outcomes ADD COLUMN auto_approved boolean NOT NULL DEFAULT false;
ALTER TABLE moderation_outcomes ADD COLUMN confidence_score double precision;
ALTER TABLE content_feedback ADD COLUMN provider varchar(32);

-- ===========================================================================
-- V66__generation_job_queue
-- ===========================================================================

-- ============================================================
-- BrainboxApi V66: durable generation job queue
-- Phase 7.5a. The router already upserts generation_jobs; this adds the durable
-- request payload and the retry schedule so any JVM (api|worker|both) can claim,
-- retry and reclaim work without sharing request threads.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: plain ALTERs, snake_case, no
-- partial indexes and no now()+interval arithmetic. next_attempt_at is nullable:
-- null means immediately claimable.
-- ============================================================

ALTER TABLE generation_jobs ADD COLUMN request_payload text;
ALTER TABLE generation_jobs ADD COLUMN max_attempts integer NOT NULL DEFAULT 5;
ALTER TABLE generation_jobs ADD COLUMN next_attempt_at timestamp with time zone;

-- ===========================================================================
-- V67__curriculum_tier0
-- ===========================================================================

-- ============================================================
-- BrainboxApi V67: Tier 0 curriculum skeleton (Phase 7.5b-1)
-- Extends the CBC strand table into a grade -> subject -> strand ->
-- sub-strand -> topic skeleton, versioned so content can be tagged against a
-- named curriculum version. The catalogue itself is authored BrainBox data in
-- resources/curriculum/ke-cbc-v1.json, seeded deterministically and
-- idempotently by CurriculumSeeder; KICD documents are not a source.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: snake_case, app-assigned
-- UUID primary keys, created_at/updated_at/version on new tables, no partial
-- indexes and no now()+interval arithmetic.
-- ============================================================

CREATE TABLE curriculum_versions (
    id             uuid PRIMARY KEY,
    country_code   varchar(8) NOT NULL,
    curriculum     varchar(32) NOT NULL,
    curriculum_version varchar(32) NOT NULL,
    name           varchar(200) NOT NULL,
    notes          text,
    is_active      boolean NOT NULL DEFAULT false,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_curriculum_versions ON curriculum_versions (country_code, curriculum, curriculum_version);

ALTER TABLE cbc_strands ADD COLUMN parent_id uuid REFERENCES cbc_strands (id) ON DELETE CASCADE;
ALTER TABLE cbc_strands ADD COLUMN level varchar(16) NOT NULL DEFAULT 'STRAND' CHECK (level IN ('STRAND','SUBSTRAND'));
ALTER TABLE cbc_strands ADD COLUMN curriculum_version varchar(32);

-- Codes become the stable seeding key; the legacy (code, grade_level) index is
-- kept for the existing CBC analytics lookups.
CREATE UNIQUE INDEX uq_cbc_strands_code ON cbc_strands (code);
CREATE INDEX ix_cbc_strands_parent ON cbc_strands (parent_id, sort_order);

ALTER TABLE curriculum_map ADD COLUMN curriculum_version varchar(32);

-- ===========================================================================
-- V68__answer_key_verification
-- ===========================================================================

-- ============================================================
-- BrainboxApi V68: independent answer-key verification (Phase 7.5f)
-- Auto-approval is the default bulk path, so a present-but-wrong answer key must
-- not ship. A second, separate model interaction solves each question without the
-- stored key; the unit records the agreement ratio, when it was verified and which
-- model verified it. The machine gate refuses an assessment unless every key
-- agrees, so an unverified or disagreeing unit stays UNREVIEWED in the exception
-- queue. answer_key_agreement is double precision to match the entity Double and
-- pass ddl-auto: validate on H2 (PostgreSQL mode) and PostgreSQL.
-- ============================================================

ALTER TABLE content_units ADD COLUMN answer_key_agreement double precision;
ALTER TABLE content_units ADD COLUMN answer_key_verified_at timestamp with time zone;
ALTER TABLE content_units ADD COLUMN answer_key_verified_model varchar(64);

-- ===========================================================================
-- V69__answer_key_disposition
-- ===========================================================================

-- ============================================================
-- BrainboxApi V69: per-question disposition of disputed answer keys (Phase 7.5h)
-- The independent answer-key solve (V68) recorded a whole-unit agreement ratio, so
-- one or two bad items discarded an otherwise accurate quiz. The disposition is now
-- per question: drop the disputed items, keep the rest, and publish only when every
-- surviving key agrees and the question floor is still met.
-- answer_key_dropped counts the items removed from content_unit_questions;
-- answer_key_dropped_detail is the JSON audit of what was removed
-- ({orderIndex, text, storedKey, verifiedAnswer}). Both columns are additive and
-- default to the empty disposition, and the types pass ddl-auto: validate on H2
-- (PostgreSQL mode) and PostgreSQL.
-- ============================================================

ALTER TABLE content_units ADD COLUMN answer_key_dropped integer NOT NULL DEFAULT 0;
ALTER TABLE content_units ADD COLUMN answer_key_dropped_detail text;

-- ===========================================================================
-- V70__generation_job_source
-- ===========================================================================

-- ============================================================
-- BrainboxApi V70: generation job source classification (H2)
-- Phase H2. The worker previously drained every queued job identically, so an
-- operator could not keep serving user requests while pausing proactive/batch
-- material generation. Each job now records where it came from:
--   USER      - a teacher/student request (interactive)
--   BATCH     - the Tier 1 batch producer (notes/learning material ahead of demand)
--   PROACTIVE - reserved for the future autonomous agent
-- The claim index supports the worker's source filter and its USER-first
-- ordering; existing rows default to USER so behaviour is unchanged until a
-- client records a different source.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: plain ALTER, snake_case, a
-- varchar CHECK and a plain composite index.
-- ============================================================

ALTER TABLE generation_jobs ADD COLUMN source varchar(16) NOT NULL DEFAULT 'USER' CHECK (source IN ('USER','BATCH','PROACTIVE'));
CREATE INDEX ix_generation_jobs_claim ON generation_jobs (status, source, created_at);

-- ===========================================================================
-- V71__generation_job_school_budget
-- ===========================================================================

-- ============================================================
-- BrainboxApi V71: per-school attribution for generation jobs (H3)
-- Phase H3 daily generation budgets. Every generation_jobs row records the
-- requesting user's school when known so a per-school daily budget can be
-- counted. Platform batch/proactive work leaves it null and counts only against
-- the platform-wide budget. ON DELETE SET NULL keeps the job rows when a school
-- is deleted; the composite index supports the (school_id, created_at) day count.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: plain ALTER, snake_case, a
-- nullable uuid FK and a plain composite index.
-- ============================================================

ALTER TABLE generation_jobs ADD COLUMN school_id uuid REFERENCES schools (id) ON DELETE SET NULL;
CREATE INDEX ix_generation_jobs_school_created ON generation_jobs (school_id, created_at);

-- ===========================================================================
-- V72__practice_paper_terminology
-- ===========================================================================

-- ============================================================
-- BrainboxApi V72: practice-paper terminology
--
-- Brainbox generates its own practice papers and never reproduces KNEC
-- examination papers. The stored exam type and homework submission type move
-- from the earlier wording to PRACTICE_PAPER. V6/V12 are already applied, so
-- their inline CHECK constraints cannot be edited in place; each old check is
-- dropped by both its PostgreSQL name (<table>_<column>_check) and its H2
-- generated name (content-derived and stable for the pinned H2 2.4.240), the
-- rows are migrated, and a named check replaces it.
--
-- There is no past-paper-specific table: the term lived in the exams.exam_type
-- and homework.submission_type enum values, the homework link column and the
-- HTTP routes. Only the column and the two CHECKs need a schema change.
-- ============================================================

-- exams.exam_type: PAST_PAPER -> PRACTICE_PAPER
ALTER TABLE exams DROP CONSTRAINT IF EXISTS exams_exam_type_check;
ALTER TABLE exams DROP CONSTRAINT IF EXISTS "CONSTRAINT_5C7";
UPDATE exams SET exam_type = 'PRACTICE_PAPER' WHERE exam_type = 'PAST_PAPER';
ALTER TABLE exams ADD CONSTRAINT ck_exams_exam_type
    CHECK (exam_type IN ('DIGITAL','PRACTICE_PAPER','TRADITIONAL','QUIZ'));

-- homework.submission_type: PAST_PAPER_REVIEW -> PRACTICE_PAPER_REVIEW
ALTER TABLE homework DROP CONSTRAINT IF EXISTS homework_submission_type_check;
ALTER TABLE homework DROP CONSTRAINT IF EXISTS "CONSTRAINT_E31534";
UPDATE homework SET submission_type = 'PRACTICE_PAPER_REVIEW' WHERE submission_type = 'PAST_PAPER_REVIEW';
ALTER TABLE homework ADD CONSTRAINT ck_homework_submission_type
    CHECK (submission_type IN ('FREE_TEXT','PRACTICE_PAPER_REVIEW','EXAM_QUESTION_SET','CHECKLIST','OFFLINE_PHYSICAL_HANDIN'));

-- homework unlocked flag renamed to is_practice_paper_unlocked
ALTER TABLE homework RENAME COLUMN is_past_paper_unlocked TO is_practice_paper_unlocked;

-- ===========================================================================
-- V73__ops_metric_rollup
-- ===========================================================================

-- ============================================================
-- BrainboxApi V73: operations metric rollups and sampled auto-approval audit (O1)
-- The backend owns its operational history in its own database. A scheduled job
-- aggregates the content/cost facts we already store into ops_metric_rollup for
-- the previous complete hour; the admin ops API serves the rollups (history) plus
-- live actuator/Micrometer values. No external observability service or new
-- dependency. moderation_outcomes.audit_sample flags the deterministic sample of
-- machine approvals a human should spot-check.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: uuid PK with created_at,
-- updated_at and version from BaseEntity, a plain unique composite index for the
-- idempotent per-hour upsert, a double precision value and a boolean default.
-- ============================================================

CREATE TABLE ops_metric_rollup (
    id           uuid PRIMARY KEY,
    bucket_start timestamp with time zone NOT NULL,
    metric       varchar(96) NOT NULL,
    dimension    varchar(160) NOT NULL DEFAULT '',
    -- "value" is quoted because VALUE is an H2 reserved word; PostgreSQL accepts
    -- the quoted lowercase name unchanged.
    "value"      double precision NOT NULL,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

-- One row per (hour, metric, dimension): recomputing an hour replaces it.
CREATE UNIQUE INDEX uq_ops_metric_rollup_bucket
    ON ops_metric_rollup (bucket_start, metric, dimension);

ALTER TABLE moderation_outcomes ADD COLUMN audit_sample boolean NOT NULL DEFAULT false;

-- ===========================================================================
-- V74__auto_approve_blocked_reason
-- ===========================================================================

-- ============================================================
-- BrainboxApi V74: persisted auto-approval exception reason (O1)
-- The pre-launch week watches the human exception queue, so the reason a unit
-- failed the machine gate must be a queryable fact, not an in-process Micrometer
-- counter that reads zero for the first hour and resets on restart. The reason is
-- the first blocker finding code, else the first finding code when the validator
-- score is below the bar, else a stable gate code (see AutoApprovalService); it is
-- cleared to null as soon as the unit auto-approves. The ops rollup aggregates the
-- column among still-UNREVIEWED units, so the reason mix is database-derived and
-- restart-safe.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: a nullable varchar(64) with no
-- default, and a plain composite b-tree index. Both engines index null keys, and
-- the ops query filters auto_approve_blocked_reason IS NOT NULL and groups by the
-- reason, so the (review_state, auto_approve_blocked_reason) index is usable and
-- cannot fail on a null reason.
-- ============================================================

ALTER TABLE content_units ADD COLUMN auto_approve_blocked_reason varchar(64);

CREATE INDEX ix_content_units_review_autoapprove_reason
    ON content_units (review_state, auto_approve_blocked_reason);

-- ===========================================================================
-- V75__generation_job_task_loop
-- ===========================================================================

-- Phase 7.5 supervisor task loop: persist the bounded generate/validate/revise
-- state on the durable job so a crash resumes the loop instead of restarting it.
ALTER TABLE generation_jobs ADD COLUMN loop_iterations integer NOT NULL DEFAULT 0;
ALTER TABLE generation_jobs ADD COLUMN loop_cost_micros bigint NOT NULL DEFAULT 0;
ALTER TABLE generation_jobs ADD COLUMN loop_feedback text;

-- ===========================================================================
-- V76__content_critique
-- ===========================================================================

-- Phase 7.5 LLM critic: a separate pedagogy judgement on the assembled unit,
-- alongside the deterministic validators and the independent answer-key solve.
ALTER TABLE content_units ADD COLUMN critique_score double precision;
ALTER TABLE content_units ADD COLUMN critique_at timestamp with time zone;
ALTER TABLE content_units ADD COLUMN critique_model varchar(64);
ALTER TABLE content_units ADD COLUMN critique_findings text;

-- ===========================================================================
-- V77__content_unit_scope
-- ===========================================================================

-- Phase 7.5 scope inheritance: generated content can be GLOBAL, SCHOOL or
-- SCHOOL_GRADE_CLASS, so the existing visibility rules apply to it too.
ALTER TABLE content_units ADD COLUMN scope varchar(24) NOT NULL DEFAULT 'GLOBAL';
ALTER TABLE content_units ADD COLUMN school_id uuid;

-- ===========================================================================
-- V78__content_schema_version
-- ===========================================================================

-- Phase 7.5 content JSON schema provenance: which contract produced the unit.
ALTER TABLE content_units ADD COLUMN content_schema_version varchar(32);

-- ===========================================================================
-- V79__content_figures
-- ===========================================================================

-- Phase 7.5 server-rendered figures. A unit step or question stores the
-- declarative spec the model authored plus the SVG the server rendered from it;
-- an exam question only needs the rendered SVG for its practice-paper section.
ALTER TABLE content_unit_steps ADD COLUMN figure_spec text;
ALTER TABLE content_unit_questions ADD COLUMN figure_spec text;
ALTER TABLE content_unit_questions ADD COLUMN figure_svg text;
ALTER TABLE exam_questions ADD COLUMN figure_svg text;

-- ===========================================================================
-- V80__exam_question_figure_spec
-- ===========================================================================

-- The practice-paper projection now carries the declarative figure spec next
-- to the rendered SVG, so a client can render the figure natively.
ALTER TABLE exam_questions ADD COLUMN figure_spec text;

-- ===========================================================================
-- V81__agent_run_school
-- ===========================================================================

-- H3 cost budget: attribute a generation/verification/critique run to the
-- school that requested it, so per-school spend is a cheap indexed join and
-- platform-scope work (school_id null) is charged to the platform only.
ALTER TABLE agent_runs ADD COLUMN school_id uuid;
CREATE INDEX ix_agent_runs_school ON agent_runs (school_id, created_at);

-- ===========================================================================
-- V82__roster_only_learners
-- ===========================================================================

-- Roster-only learners: a class teacher (or a coordinator/ICT admin) can
-- provision a real student record for a pupil who has no smartphone, so the
-- traditional exam engine can enter marks, rank and print reports for them.
-- The account is login-disabled (account_kind = ROSTER_ONLY) and can later be
-- upgraded to FULL when the learner gets a phone.
ALTER TABLE users ADD COLUMN account_kind varchar(16) NOT NULL DEFAULT 'FULL';
ALTER TABLE users ADD COLUMN provisioned_by uuid;
ALTER TABLE users ADD COLUMN provisioned_at timestamp with time zone;
ALTER TABLE users ADD COLUMN guardian_name varchar(160);
ALTER TABLE users ADD COLUMN guardian_phone varchar(32);
CREATE INDEX ix_users_school_account_kind ON users (school_id, account_kind);

-- ===========================================================================
-- V83__provisioned_learner_class
-- ===========================================================================

-- Roster-only learners are deliberately NOT class_memberships: that is what
-- keeps them out of attendance, gradebook, homework, CBC analytics, messaging
-- fan-out and every other class/grade feature by construction. Their class is
-- recorded here only so traditional per-class reports can tag them correctly.
ALTER TABLE users ADD COLUMN provisioned_class_id uuid REFERENCES teacher_classes (id) ON DELETE SET NULL;
CREATE INDEX ix_users_provisioned_class ON users (provisioned_class_id);

-- ===========================================================================
-- V84__payments
-- ===========================================================================

-- IntaSend/M-Pesa payment relay (doc 14 section 6). One row per STK attempt
-- with an idempotent activation keyed on the provider invoice id, plus an
-- append-only subscription history. Keys are configuration, not schema, so
-- deployment swaps IntaSend test keys for live ones with environment variables.
ALTER TABLE subscriptions ADD COLUMN mpesa_transaction_id varchar(64);

CREATE TABLE payment_transactions (
    id                uuid PRIMARY KEY,
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tier              varchar(16) NOT NULL,
    amount            integer NOT NULL,
    currency          varchar(8) NOT NULL DEFAULT 'KES',
    phone_number      varchar(16) NOT NULL,
    provider          varchar(24) NOT NULL DEFAULT 'INTASEND',
    provider_ref      varchar(64),
    status            varchar(16) NOT NULL DEFAULT 'PENDING',
    failure_reason    text,
    client_request_id varchar(64),
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_payment_tx_user ON payment_transactions (user_id, created_at);
CREATE UNIQUE INDEX uq_payment_tx_provider_ref ON payment_transactions (provider_ref);
CREATE UNIQUE INDEX uq_payment_tx_client_request ON payment_transactions (user_id, client_request_id);

CREATE TABLE subscription_history (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    action         varchar(16) NOT NULL,
    tier           varchar(16) NOT NULL,
    amount         integer NOT NULL DEFAULT 0,
    transaction_id varchar(64),
    created_at     timestamp with time zone NOT NULL DEFAULT now()
);
CREATE INDEX ix_subscription_history_user ON subscription_history (user_id, created_at);

-- ===========================================================================
-- V85__media_uploads
-- ===========================================================================

-- Presigned client-direct uploads (Phase 6). The API signs a short-lived PUT
-- URL so the bytes go straight to S3/MinIO; one row per ticket lets confirm
-- verify the real format before the file is served, and lets the sweeper delete
-- an abandoned or rejected object.
CREATE TABLE media_uploads (
    id            uuid PRIMARY KEY,
    owner_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    storage_key   varchar(120) NOT NULL,
    declared_kind varchar(16) NOT NULL,
    purpose       varchar(32) NOT NULL,
    status        varchar(16) NOT NULL DEFAULT 'PENDING',
    size_bytes    bigint,
    expires_at    timestamp with time zone NOT NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_media_uploads_owner ON media_uploads (owner_id, created_at);
CREATE INDEX ix_media_uploads_sweep ON media_uploads (status, expires_at);

-- ===========================================================================
-- V86__content_unit_strands
-- ===========================================================================

-- Phase 7.5b: generated content carries the CBC strand and sub-strand the teacher
-- picked (codes from the seeded cbc_strands catalogue), so the projection can align
-- learner content by code instead of only deriving a strand name from the concept.
ALTER TABLE content_units ADD COLUMN cbc_strand varchar(32);
ALTER TABLE content_units ADD COLUMN cbc_sub_strand varchar(32);

-- ===========================================================================
-- V87__personal_practice_papers
-- ===========================================================================

-- Phase 7 §B7: a learner can generate a practice paper on demand. It is an exams row
-- scoped PERSONAL to its owner, so it never appears in the shared exam hub, practice-paper
-- browse or another learner's reads.
ALTER TABLE exams ADD COLUMN owner_user_id uuid REFERENCES users (id);
CREATE INDEX ix_exams_owner ON exams (owner_user_id);

-- V6 pinned scope to the three shared spellings; a personal paper needs its own.
-- As in V72, the old check is dropped by both its PostgreSQL name
-- (<table>_<column>_check) and its H2 generated name (content-derived and stable for
-- the pinned H2 2.4.240), since Flyway runs the same script against both engines.
ALTER TABLE exams DROP CONSTRAINT IF EXISTS exams_scope_check;
ALTER TABLE exams DROP CONSTRAINT IF EXISTS "CONSTRAINT_5C74";
ALTER TABLE exams ADD CONSTRAINT ck_exams_scope
    CHECK (scope IN ('GLOBAL','SCHOOL','SCHOOL_GRADE_CLASS','PERSONAL'));

-- ===========================================================================
-- V88__media_scan_verdict
-- ===========================================================================

-- Malware/URL scanning stage for uploads. The presigned confirm path (and the proxied
-- multipart path) now records a verdict before an object may be served, so a deployment
-- can tell "scanned clean" from "no scanner configured". Existing rows default to
-- SKIPPED: they were never checked, and the schema says so.
ALTER TABLE media_uploads ADD COLUMN scan_status varchar(16) NOT NULL DEFAULT 'SKIPPED';
ALTER TABLE media_uploads ADD COLUMN scan_detail varchar(255);
ALTER TABLE media_uploads ADD COLUMN scanner varchar(16);

-- The verdict is a closed set, so a typo cannot create a status no reader understands.
ALTER TABLE media_uploads ADD CONSTRAINT ck_media_uploads_scan_status
    CHECK (scan_status IN ('CLEAN', 'INFECTED', 'SKIPPED', 'ERROR'));

-- ===========================================================================
-- V89__subject_agent_attribution
-- ===========================================================================

-- Per-subject domain agents (docs/PHASE7_AGENT_ARCHITECTURE.md 2.3). The router already
-- appended a subject persona; it now also records which agent and which prompt version
-- produced a unit, so the capture tables (and the console views over them) can attribute
-- quality to an agent rather than to "the model".
ALTER TABLE agent_runs ADD COLUMN agent_code varchar(32);
ALTER TABLE content_units ADD COLUMN agent_code varchar(32);

-- Reporting by agent is the point of the column, so index it on both sides.
CREATE INDEX ix_agent_runs_agent ON agent_runs (agent_code);
CREATE INDEX ix_content_units_agent ON content_units (agent_code);

-- ===========================================================================
-- V90__media_security_console
-- ===========================================================================

-- Media security for the admin console: every scan is logged, anything that fails or is
-- refused raises an alert an operator can acknowledge, and an infected object is
-- quarantined (kept out of serving but inspectable) instead of vanishing. The settings
-- that drive this live in moderation_policies as runtime keys, so ClamAV can be switched
-- on or off per deployment and per environment without a release.
--
-- Existing behaviour is unchanged for a deployment with no scanner configured: no scans,
-- no alerts, no quarantine rows.

CREATE TABLE media_scan_logs (
    id            uuid PRIMARY KEY,
    upload_id     uuid,
    storage_key   varchar(120) NOT NULL,
    owner_id      uuid,
    provider      varchar(32) NOT NULL,
    status        varchar(16) NOT NULL
                  CHECK (status IN ('CLEAN', 'INFECTED', 'SKIPPED', 'ERROR')),
    detail        varchar(255),
    size_bytes    bigint,
    content_type  varchar(128),
    duration_ms   bigint NOT NULL DEFAULT 0,
    -- What the policy did with the verdict: ACCEPTED, QUARANTINED, DELETED or REFUSED.
    action        varchar(16) NOT NULL DEFAULT 'ACCEPTED'
                  CHECK (action IN ('ACCEPTED', 'QUARANTINED', 'DELETED', 'REFUSED')),
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_media_scan_logs_created ON media_scan_logs (created_at);
CREATE INDEX ix_media_scan_logs_status ON media_scan_logs (status, created_at);

CREATE TABLE media_security_alerts (
    id              uuid PRIMARY KEY,
    kind            varchar(32) NOT NULL
                    CHECK (kind IN ('INFECTED', 'SCAN_ERROR', 'URL_BLOCKED', 'URL_ERROR', 'QUARANTINE')),
    severity        varchar(16) NOT NULL DEFAULT 'HIGH'
                    CHECK (severity IN ('HIGH', 'MEDIUM', 'LOW')),
    upload_id       uuid,
    storage_key     varchar(255),
    url             varchar(512),
    owner_id        uuid,
    provider        varchar(32),
    detail          varchar(500),
    acknowledged    boolean NOT NULL DEFAULT FALSE,
    acknowledged_by uuid,
    acknowledged_at timestamp with time zone,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0
);
-- The console's default view is the unacknowledged queue, newest first.
CREATE INDEX ix_media_alerts_open ON media_security_alerts (acknowledged, created_at);

CREATE TABLE media_quarantine (
    id             uuid PRIMARY KEY,
    upload_id      uuid,
    original_key   varchar(120) NOT NULL,
    quarantine_key varchar(160) NOT NULL,
    owner_id       uuid,
    reason         varchar(32) NOT NULL
                   CHECK (reason IN ('INFECTED', 'SCAN_ERROR', 'POLICY')),
    detail         varchar(255),
    size_bytes     bigint,
    content_type   varchar(128),
    status         varchar(16) NOT NULL DEFAULT 'QUARANTINED'
                   CHECK (status IN ('QUARANTINED', 'RESTORED', 'DELETED')),
    resolved_by    uuid,
    resolved_at    timestamp with time zone,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_media_quarantine_status ON media_quarantine (status, created_at);

-- The ticket itself records what the scan policy did, so an operator can see the outcome
-- without joining the scan log, and whether the bytes are held in quarantine.
ALTER TABLE media_uploads ADD COLUMN scan_action varchar(16);
ALTER TABLE media_uploads ADD COLUMN quarantine_id uuid;

-- ===========================================================================
-- V91__console_platform_permissions
-- ===========================================================================

-- Console security. A blanket ADMIN role is not enough to operate a platform console:
-- an account must hold the specific platform permission for what it is doing. Permissions
-- are a small, closed set stored on the account, and every operator action is audited.
--
-- Secure by default: no account holds a permission until one is granted. The first operator
-- is bootstrapped from configuration (`app.console.bootstrap-operator-phones`) and the
-- application logs a warning while there is no operator at all.
ALTER TABLE users ADD COLUMN platform_permissions varchar(255);

-- Platform audit. `audit_logs` was school-scoped; a console action has no school, so the
-- column becomes nullable and the row learns what it acted on. One audit trail, one
-- retention scheduler.
ALTER TABLE audit_logs ALTER COLUMN school_id DROP NOT NULL;
ALTER TABLE audit_logs ADD COLUMN target varchar(200);
ALTER TABLE audit_logs ADD COLUMN detail varchar(500);
CREATE INDEX ix_audit_logs_created ON audit_logs (created_at);

-- ===========================================================================
-- V92__console_roles_and_console_surfaces
-- ===========================================================================

-- Platform console roles: a named set of capabilities an ADMIN can create and assign.
-- Capabilities are the unit of authority (see PlatformPermission in code); a role is how an
-- operator hands a coherent set of them to an account. `is_system` protects the seeded roles
-- from deletion, so the console always has a way back in.
CREATE TABLE console_roles (
    id           uuid PRIMARY KEY,
    name         varchar(64) NOT NULL UNIQUE,
    description  varchar(255) NOT NULL DEFAULT '',
    capabilities varchar(512) NOT NULL DEFAULT '',
    -- A system role cannot be renamed or deleted; only its capability set is editable.
    is_system    boolean NOT NULL DEFAULT FALSE,
    created_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

-- An account's console role, in addition to any per-account capability extras it was granted.
ALTER TABLE users ADD COLUMN console_role_id uuid REFERENCES console_roles (id) ON DELETE SET NULL;
CREATE INDEX ix_users_console_role ON users (console_role_id);

-- Notification composition: a targeted message with an optional schedule, and the automation
-- rules that produce one without a human.
CREATE TABLE console_notifications (
    id            uuid PRIMARY KEY,
    title         varchar(200) NOT NULL,
    body          varchar(2000) NOT NULL,
    -- NOTIFICATION writes an in-app notification (and pushes); MESSAGE writes an inbox message
    -- from the platform's own Brainbox account, so it arrives like any other message.
    channel       varchar(16) NOT NULL DEFAULT 'NOTIFICATION'
                  CHECK (channel IN ('NOTIFICATION', 'MESSAGE')),
    -- ALL, ROLE, SCHOOL, GRADE, CLASS or USER.
    audience_type varchar(16) NOT NULL
                  CHECK (audience_type IN ('ALL', 'ROLE', 'SCHOOL', 'GRADE', 'CLASS', 'USER')),
    -- The audience value: a role name, a school id, a grade, a class id or a user id.
    audience_value varchar(120),
    -- Sent immediately when null, otherwise the scheduler sends it when due.
    scheduled_at  timestamp with time zone,
    status        varchar(16) NOT NULL DEFAULT 'SCHEDULED'
                  CHECK (status IN ('SCHEDULED', 'SENT', 'CANCELLED', 'FAILED')),
    recipient_count int NOT NULL DEFAULT 0,
    sent_at       timestamp with time zone,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_console_notifications_due ON console_notifications (status, scheduled_at);

CREATE TABLE console_automation_rules (
    id            uuid PRIMARY KEY,
    name          varchar(120) NOT NULL,
    -- The trigger the scheduler evaluates: SUBSCRIPTION_EXPIRING, SUBSCRIPTION_EXPIRED.
    trigger_type  varchar(32) NOT NULL
                  CHECK (trigger_type IN ('SUBSCRIPTION_EXPIRING', 'SUBSCRIPTION_EXPIRED')),
    -- Days before (expiring) the trigger fires.
    threshold_days int NOT NULL DEFAULT 7,
    title         varchar(200) NOT NULL,
    body          varchar(2000) NOT NULL,
    enabled       boolean NOT NULL DEFAULT TRUE,
    last_run_at   timestamp with time zone,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

-- Per-subject agent prompt overrides. The registry in code stays the default; a row here is
-- the console's edit of one agent, versioned so a bad prompt can be traced and rolled back.
CREATE TABLE subject_agent_prompts (
    id                   uuid PRIMARY KEY,
    agent_code           varchar(32) NOT NULL UNIQUE,
    persona              varchar(4000) NOT NULL,
    assessment_guidance  varchar(2000) NOT NULL DEFAULT '',
    notes_guidance       varchar(2000) NOT NULL DEFAULT '',
    prompt_version       int NOT NULL DEFAULT 1,
    updated_by           uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at           timestamp with time zone NOT NULL DEFAULT now(),
    updated_at           timestamp with time zone NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0
);

-- The seeded roles: one all-powerful operator, and read-only roles a first operator can hand
-- out immediately. Everything else is created in the console.
INSERT INTO console_roles (id, name, description, capabilities, is_system) VALUES
  ('9f000000-0000-4000-8000-000000000001', 'PLATFORM_OWNER', 'Full console authority', 'PLATFORM_ADMIN', TRUE),
  ('9f000000-0000-4000-8000-000000000002', 'OPERATIONS', 'Day-to-day operations: users, schools, public content, messaging, subscribers', 'CONSOLE_READ,USERS_READ,USERS_MANAGE,SCHOOLS_READ,SCHOOLS_MANAGE,NEWS_MANAGE,NOTIFICATIONS_MANAGE,MESSAGES_MANAGE,SUBSCRIBERS_READ,CONTENT_AGENTS_MANAGE', TRUE),
  ('9f000000-0000-4000-8000-000000000003', 'SECURITY_OFFICER', 'Media security posture, alert queue and quarantine', 'CONSOLE_READ,SECURITY_READ,SECURITY_OPERATE', TRUE),
  ('9f000000-0000-4000-8000-000000000004', 'SUPPORT_READONLY', 'Read-only support view', 'CONSOLE_READ,USERS_READ,SCHOOLS_READ,SUBSCRIBERS_READ,SECURITY_READ', TRUE);

-- The platform's own account: the sender of every message Brainbox writes into a user's inbox.
-- It cannot sign in (the password hash is a random secret nobody holds) and holds no console
-- capability, so the account exists only as a message author. The id is fixed so the messaging
-- layer can present its messages as coming from the platform rather than an administrator.
INSERT INTO users (id, phone_number, email, password_hash, name, role, is_active, is_verified,
                   verification_status, account_kind)
SELECT '00000000-0000-4000-8000-00000000b0b0', NULL, 'brainbox@platform.local',
       '$2a$10$platformsenderaccountcannotlogin0000000000000000000000000000',
       'Brainbox', 'ADMIN', TRUE, TRUE, 'VERIFIED', 'FULL'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = '00000000-0000-4000-8000-00000000b0b0');
