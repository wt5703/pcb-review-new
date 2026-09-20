ALTER TABLE review_task ADD COLUMN designer_name VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE review_task ADD COLUMN expected_completed_date DATE;
ALTER TABLE review_task ADD COLUMN expert_leader_id BIGINT;
ALTER TABLE review_task ADD COLUMN expert_leader_name VARCHAR(100);
ALTER TABLE review_task ADD COLUMN review_roles VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE review_task ADD COLUMN review_description VARCHAR(2000);

ALTER TABLE review_opinion ADD COLUMN image_url VARCHAR(1000);

CREATE INDEX idx_review_task_designer_name ON review_task (designer_name);
