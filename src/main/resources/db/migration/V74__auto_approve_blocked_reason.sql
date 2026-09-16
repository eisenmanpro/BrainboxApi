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
