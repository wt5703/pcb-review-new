CREATE TABLE review_task (
    id BIGINT PRIMARY KEY,
    review_type VARCHAR(32) NOT NULL,
    task_name VARCHAR(200) NOT NULL,
    project_name VARCHAR(200) NOT NULL,
    designer_id BIGINT NOT NULL,
    design_name VARCHAR(200) NOT NULL,
    pcb_type VARCHAR(64),
    status VARCHAR(64) NOT NULL,
    initial_file_ids VARCHAR(1000) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_review_task_query ON review_task (review_type, status, designer_id);
