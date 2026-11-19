CREATE TABLE review_submission (
                                   id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   task_id BIGINT NOT NULL COMMENT '评审任务 ID',
                                   delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改',
                                   source_type VARCHAR(64) NOT NULL COMMENT '评审来源；互检统一使用 MUTUAL_CHECK_ITEM',
                                   reviewer_employee_no VARCHAR(64) NOT NULL COMMENT '点击评审完成的人员工号',
                                   submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点击评审完成的时间',
                                   active_source_type VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN delete_status=0 THEN source_type ELSE NULL END)
) COMMENT='评审完成记录；重新开启阶段时逻辑删除旧记录，重新完成时新增';


ALTER TABLE task_flow_record
    ADD COLUMN from_status VARCHAR(64),
    ADD COLUMN to_status VARCHAR(64),
    ADD COLUMN next_operator_employee_nos TEXT,
    ADD COLUMN approval_role VARCHAR(64),
    ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';

ALTER TABLE check_item_template
    MODIFY COLUMN item_name TEXT,
    ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';

ALTER TABLE review_task
    MODIFY COLUMN id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';

ALTER TABLE outbox_event
    ADD INDEX idx_outbox_event_aggregate (aggregate_type, aggregate_id),
    ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';

-- 2. 仅约束有效互检快照；逻辑删除后可新增快照，原记录保留。
ALTER TABLE task_check_item
    ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除',
DROP INDEX uk_task_check_item,
    ADD COLUMN active_item_id BIGINT GENERATED ALWAYS AS (CASE WHEN delete_status=0 THEN item_id ELSE NULL END),
    ADD UNIQUE KEY uk_task_check_item (task_id, active_item_id);

-- 3. 其余业务表统一增加逻辑删除标记，普通业务更新不得修改。
ALTER TABLE review_opinion ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE review_file ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE opinion_reply ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE opinion_confirmation ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE notification_send_record ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE task_archive_snapshot ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE reviewer_whitelist ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE reviewer_whitelist_role ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
UPDATE reviewer_whitelist SET delete_status=1 WHERE deleted=TRUE;
UPDATE reviewer_whitelist_role SET delete_status=1 WHERE deleted=TRUE;

