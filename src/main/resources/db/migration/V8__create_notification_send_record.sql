CREATE TABLE notification_send_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    outbox_event_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    recipient VARCHAR(320) NOT NULL,
    template_code VARCHAR(128) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(2000),
    attempted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_send_record_outbox ON notification_send_record (outbox_event_id, attempted_at);
