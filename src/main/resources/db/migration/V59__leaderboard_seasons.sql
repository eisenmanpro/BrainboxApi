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
