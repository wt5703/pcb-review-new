CREATE TABLE review_file (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    file_category VARCHAR(32) NOT NULL,
    business_file_key VARCHAR(128) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    file_size BIGINT NOT NULL,
    md5 VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    company_file_id VARCHAR(128) NOT NULL,
    is_latest BOOLEAN NOT NULL,
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (task_id, file_category, business_file_key, version_no)
);

CREATE INDEX idx_review_file_latest ON review_file (task_id, file_category, business_file_key, is_latest);
