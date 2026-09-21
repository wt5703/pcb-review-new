-- 旧 review_file.version_no 是历史表结构中的非空列；文件现改为原地覆盖，保留列仅为兼容已升级数据库。
ALTER TABLE review_file ALTER COLUMN version_no SET DEFAULT 1;
ALTER TABLE review_opinion DROP COLUMN file_version_id;
ALTER TABLE review_opinion DROP COLUMN version;
ALTER TABLE opinion_reply DROP COLUMN file_version_id;
