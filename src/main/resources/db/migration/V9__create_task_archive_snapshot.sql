CREATE TABLE task_archive_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE,
    final_status VARCHAR(64) NOT NULL,
    task_snapshot TEXT NOT NULL,
    file_snapshot TEXT NOT NULL,
    reviewer_snapshot TEXT NOT NULL,
    opinion_snapshot TEXT NOT NULL,
    flow_snapshot TEXT NOT NULL,
    notification_snapshot TEXT NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
