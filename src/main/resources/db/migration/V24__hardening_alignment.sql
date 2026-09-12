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
