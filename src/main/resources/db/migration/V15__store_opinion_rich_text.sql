ALTER TABLE review_opinion MODIFY COLUMN content LONGTEXT NOT NULL;
ALTER TABLE review_opinion DROP COLUMN image_url;
