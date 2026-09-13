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
