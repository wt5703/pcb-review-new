CREATE TABLE check_item_template (
    id BIGINT PRIMARY KEY,
    review_type VARCHAR(32) NOT NULL,
    item_key VARCHAR(128) NOT NULL,
    parent_item_key VARCHAR(128),
    item_name VARCHAR(500) NOT NULL,
    sort_no INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (review_type, item_key)
);

CREATE TABLE task_check_item (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    template_item_id BIGINT NOT NULL,
    template_item_key VARCHAR(128) NOT NULL,
    parent_item_key VARCHAR(128),
    item_name VARCHAR(500) NOT NULL,
    sort_no INTEGER NOT NULL DEFAULT 0,
    check_result VARCHAR(32),
    comment VARCHAR(2000),
    linked_opinion_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, template_item_id)
);

CREATE INDEX idx_check_template_type_enabled ON check_item_template (review_type, enabled, sort_no);
CREATE INDEX idx_task_check_item_task ON task_check_item (task_id, sort_no);
