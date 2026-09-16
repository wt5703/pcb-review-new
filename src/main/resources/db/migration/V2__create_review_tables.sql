CREATE TABLE task_reviewer (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    review_role VARCHAR(64) NOT NULL,
    reviewer_id BIGINT NOT NULL,
    process_status VARCHAR(32) NOT NULL,
    assigned_by BIGINT NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMP,
    no_opinion BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (task_id, review_role, reviewer_id)
);

CREATE TABLE review_opinion (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_item_id BIGINT,
    severity VARCHAR(32) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    raised_by BIGINT NOT NULL,
    file_version_id BIGINT,
    status VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_task_reviewer_my_task ON task_reviewer (reviewer_id, process_status, task_id);
CREATE INDEX idx_review_opinion_task_status ON review_opinion (task_id, status, raised_by);
