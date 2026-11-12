-- Attendance SMS channel, Phase 3 (docs/sms_and_attendance_notifications_plan.md section 6, D2/D5).
--
-- Additive only and dormant while app.attendance.sms.enabled=false. One dispatch row per
-- (attendance record, recipient account): the unique index is the exactly-once guard across
-- scheduler reruns and process restarts. A row is written only when the channel actually
-- attempts delivery, so the ledger is the spend and delivery record, not a skip log.
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS attendance_sms_opt_in boolean NOT NULL DEFAULT FALSE;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS attendance_sms_consent_at timestamp with time zone;

CREATE TABLE IF NOT EXISTS attendance_sms_dispatches (
    id                    uuid PRIMARY KEY,
    attendance_record_id  uuid NOT NULL REFERENCES attendance_records (id) ON DELETE CASCADE,
    school_id             uuid,
    learner_id            uuid NOT NULL,
    recipient_user_id     uuid NOT NULL,
    phone_number          varchar(32) NOT NULL,
    status                varchar(24) NOT NULL,
    provider              varchar(32),
    provider_message_id   varchar(160),
    error                 varchar(512),
    units                 integer NOT NULL DEFAULT 1,
    cost_kes              numeric(12, 2) NOT NULL DEFAULT 0,
    created_at            timestamp with time zone NOT NULL DEFAULT now(),
    updated_at            timestamp with time zone NOT NULL DEFAULT now(),
    version               bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_asd_record_recipient
    ON attendance_sms_dispatches (attendance_record_id, recipient_user_id);
CREATE INDEX IF NOT EXISTS idx_asd_school_created
    ON attendance_sms_dispatches (school_id, created_at);
CREATE INDEX IF NOT EXISTS idx_asd_phone_created
    ON attendance_sms_dispatches (phone_number, created_at);

-- Per-school, per-term allocation (D5: KES 2,000/term by default). used_kes is drawn down
-- transactionally with each dispatch; SMS stops at zero while push/in-app continue.
CREATE TABLE IF NOT EXISTS school_sms_budgets (
    id                     uuid PRIMARY KEY,
    school_id              uuid NOT NULL,
    term_label             varchar(16) NOT NULL,
    budget_kes             numeric(12, 2) NOT NULL DEFAULT 0,
    used_kes               numeric(12, 2) NOT NULL DEFAULT 0,
    units_sent             integer NOT NULL DEFAULT 0,
    low_balance_alerted_at timestamp with time zone,
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    updated_at             timestamp with time zone NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ssb_school_term ON school_sms_budgets (school_id, term_label);
