CREATE TABLE task_flow_record (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    from_status VARCHAR(64) NOT NULL,
    to_status VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    operator_id BIGINT NOT NULL,
    comment VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_task_flow_record_task ON task_flow_record (task_id, created_at);
