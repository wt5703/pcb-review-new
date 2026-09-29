ALTER TABLE task_archive_snapshot ADD COLUMN IF NOT EXISTS flow_snapshot TEXT NOT NULL DEFAULT '[]';
ALTER TABLE task_archive_snapshot ADD COLUMN IF NOT EXISTS notification_snapshot TEXT NOT NULL DEFAULT '[]';
