CREATE TABLE check_item_attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    check_item_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    sort_no INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (check_item_id, file_id)
);

CREATE INDEX idx_check_item_attachment_item ON check_item_attachment (check_item_id, sort_no);
