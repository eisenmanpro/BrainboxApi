-- Audit 3 live classes. V33 is reserved for another workstream.
--
-- The recording link itself already has a column (live_classes.recording_url) and is
-- now written by POST /teacher/live-classes/{classId}/recording after the browser
-- uploads the .webm through /media/uploads. No recording column is needed.
--
-- The real host lifecycle does need persistence: without these timestamps a page
-- refresh while LIVE loses the elapsed clock, a re-started session cannot be told
-- from a fresh one, and a stale LIVE row can never be distinguished from an active
-- broadcast. Both are nullable so existing SCHEDULED/COMPLETED rows are untouched.
-- last_host_seen_at is refreshed by the host heartbeat; a stalled value lets the
-- stale-session sweeper close a broadcast nobody is hosting.
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS started_at timestamp with time zone;
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS ended_at timestamp with time zone;
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS last_host_seen_at timestamp with time zone;

-- Duration of the uploaded recording. The scheduled window is not the recording
-- length (a host may start late or stop early), so the client reports the real
-- elapsed seconds with the upload and this is what the Recordings list shows.
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS recording_duration_seconds integer;
