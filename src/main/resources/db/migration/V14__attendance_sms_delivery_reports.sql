-- Attendance SMS Phase 4 (docs/sms_and_attendance_notifications_plan.md): delivery reports and
-- a provider message-id lookup index. Additive and dormant while app.attendance.sms.enabled=false.
--
-- A DLR webhook updates these columns; a provider that sends no DLR simply leaves them null and
-- the ledger shows the dispatch as SENT (provider-accepted) rather than delivered. delivered_at is
-- set once, on the first DELIVERED report, so a replayed webhook cannot move the timestamp.

ALTER TABLE attendance_sms_dispatches ADD COLUMN IF NOT EXISTS delivered_at timestamp with time zone;
ALTER TABLE attendance_sms_dispatches ADD COLUMN IF NOT EXISTS dlr_status varchar(32);
ALTER TABLE attendance_sms_dispatches ADD COLUMN IF NOT EXISTS dlr_received_at timestamp with time zone;

CREATE INDEX IF NOT EXISTS idx_asd_provider_message_id
    ON attendance_sms_dispatches (provider_message_id);
