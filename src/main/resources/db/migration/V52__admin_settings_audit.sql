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
