-- 邮件投递日志保留任务、主题、多个收件人/抄送人和附件，失败时无需再从已变更的任务数据反推。
ALTER TABLE notification_send_record ADD COLUMN IF NOT EXISTS task_id BIGINT;
ALTER TABLE notification_send_record ADD COLUMN IF NOT EXISTS carbon_copies TEXT;
ALTER TABLE notification_send_record ADD COLUMN IF NOT EXISTS attachments TEXT;
ALTER TABLE notification_send_record ADD COLUMN IF NOT EXISTS subject VARCHAR(500);
ALTER TABLE notification_send_record MODIFY COLUMN recipient TEXT NOT NULL;
CREATE INDEX idx_notification_send_record_task ON notification_send_record (task_id, attempted_at);
