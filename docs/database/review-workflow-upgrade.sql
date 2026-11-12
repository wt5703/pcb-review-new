-- Apply once before deploying on an existing database.
ALTER TABLE task_flow_record
    ADD COLUMN from_status VARCHAR(64),
    ADD COLUMN to_status VARCHAR(64),
    ADD COLUMN next_operator_employee_nos TEXT,
    ADD COLUMN approval_role VARCHAR(64);

-- Allow full text from longer schematic checklist cells.
ALTER TABLE check_item_template MODIFY COLUMN item_name TEXT NOT NULL;

-- Preserve task identifiers after hard deletion and scope outbox locks to the deleted task.
ALTER TABLE review_task MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE outbox_event ADD INDEX idx_outbox_event_aggregate (aggregate_type, aggregate_id);
