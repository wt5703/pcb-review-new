CREATE TABLE opinion_attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    opinion_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    sort_no INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (opinion_id, file_id)
);

CREATE INDEX idx_opinion_attachment_opinion ON opinion_attachment (opinion_id, sort_no);
