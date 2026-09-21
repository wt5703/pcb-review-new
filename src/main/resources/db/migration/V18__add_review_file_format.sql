-- 文件格式独立保存，避免前端或归档页仅通过文件名猜测格式。
ALTER TABLE review_file ADD COLUMN file_format VARCHAR(32);
