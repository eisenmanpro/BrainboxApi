-- News image attribution: an optional photographer/source credit shown with the
-- article image. Nullable so existing rows and credit-less images stay valid.
ALTER TABLE news_items ADD COLUMN image_credit varchar(200);
