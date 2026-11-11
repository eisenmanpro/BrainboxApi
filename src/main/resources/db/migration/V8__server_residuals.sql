-- Server residual asks from the F5/F7/F8 audits.
-- F5-A3: a streak freeze/grace day is a persisted credit for one missed local day.
CREATE TABLE IF NOT EXISTS streak_credits (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    credit_date date NOT NULL,
    kind        varchar(16) NOT NULL,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_streak_credit_user_day ON streak_credits (user_id, credit_date);

-- F7-A1: durable analytics snapshot taken at finalize.
ALTER TABLE traditional_exams ADD COLUMN IF NOT EXISTS analytics_snapshot TEXT;
-- F7-A7: proves the classroom broadcast happened, so an outbox replay can retry a missing one.
ALTER TABLE traditional_exams ADD COLUMN IF NOT EXISTS broadcast_at TIMESTAMP WITH TIME ZONE;

-- F8-A2: one escalation per absence record, independent of the first mark notification.
CREATE TABLE IF NOT EXISTS attendance_escalations (
    id          uuid PRIMARY KEY,
    record_id   uuid NOT NULL REFERENCES attendance_records (id) ON DELETE CASCADE,
    notified_at timestamp with time zone NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_attendance_escalation_record ON attendance_escalations (record_id);
