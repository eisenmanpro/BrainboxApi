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
