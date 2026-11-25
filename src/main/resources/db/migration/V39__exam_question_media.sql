-- Exam question media: the web editor has offered a media URL/caption since the
-- builder shipped, but the server did not persist it, so images vanished on
-- reload. Persist the link, caption and an optional photographer/source credit.
ALTER TABLE exam_questions ADD COLUMN media_url varchar(1024);
ALTER TABLE exam_questions ADD COLUMN media_caption varchar(200);
ALTER TABLE exam_questions ADD COLUMN media_credit varchar(200);
