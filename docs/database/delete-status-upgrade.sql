-- 已有数据库完成此前的结构升级后执行一次；全新数据库使用更新后的 ddl.sql，无需重复执行本脚本。
-- 统一逻辑删除标记：0 未删除，1 已删除；普通业务更新不得修改此字段。

ALTER TABLE review_task ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE review_opinion ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE review_submission ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE review_file ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE opinion_reply ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE opinion_confirmation ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE check_item_template ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE task_check_item ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE task_flow_record ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE outbox_event ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE notification_send_record ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE task_archive_snapshot ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE reviewer_whitelist ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';
ALTER TABLE reviewer_whitelist_role ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改';

-- 保留原白名单删除状态；白名单继续允许恢复原记录。旧 deleted 字段仅保留历史兼容。
UPDATE reviewer_whitelist SET delete_status=1 WHERE deleted=TRUE;
UPDATE reviewer_whitelist_role SET delete_status=1 WHERE deleted=TRUE;

-- 评审完成记录改为独立主键，允许逻辑删除后新增下一轮；同一来源同一人员只允许一条有效记录。
ALTER TABLE review_submission DROP PRIMARY KEY;
ALTER TABLE review_submission ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;
ALTER TABLE review_submission ADD COLUMN active_source_type VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN delete_status=0 THEN source_type ELSE NULL END);
ALTER TABLE review_submission ADD UNIQUE KEY uk_review_submission_active (task_id, active_source_type, reviewer_employee_no);

-- 模板同步移除未填写快照后，允许将来重新生成；保留原有已删除快照。
ALTER TABLE task_check_item DROP INDEX uk_task_check_item;
ALTER TABLE task_check_item ADD COLUMN active_item_id BIGINT GENERATED ALWAYS AS (CASE WHEN delete_status=0 THEN item_id ELSE NULL END);
ALTER TABLE task_check_item ADD UNIQUE KEY uk_task_check_item (task_id, active_item_id);
