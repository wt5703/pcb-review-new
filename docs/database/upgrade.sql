-- 统一增量升级脚本：已有库执行一次；全新库使用 ddl.sql 和 init.sql。
-- 适用于已拆分白名单人员、角色表，尚未执行原三个 upgrade 脚本的数据库。
-- 已执行过部分旧脚本的库，只执行尚未应用的语句，不重复建表或添加字段。

-- 1. 流程日志及基础字段。
ALTER TABLE task_flow_record ADD COLUMN from_status VARCHAR(64);
ALTER TABLE task_flow_record ADD COLUMN to_status VARCHAR(64);
ALTER TABLE task_flow_record ADD COLUMN next_operator_employee_nos TEXT;
ALTER TABLE task_flow_record ADD COLUMN approval_role VARCHAR(64);
ALTER TABLE check_item_template MODIFY COLUMN item_name TEXT NOT NULL;
ALTER TABLE review_task MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE outbox_event ADD INDEX idx_outbox_event_aggregate (aggregate_type, aggregate_id);

-- 2. 现有业务表统一增加逻辑删除标记，普通业务更新不得修改。
ALTER TABLE review_task ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE review_opinion ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE review_file ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE opinion_reply ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE opinion_confirmation ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE check_item_template ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE task_check_item ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE task_flow_record ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE outbox_event ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE notification_send_record ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE task_archive_snapshot ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE reviewer_whitelist ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
ALTER TABLE reviewer_whitelist_role ADD COLUMN delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除';
UPDATE reviewer_whitelist SET delete_status=1 WHERE deleted=TRUE;
UPDATE reviewer_whitelist_role SET delete_status=1 WHERE deleted=TRUE;

-- 3. review_submission 直接创建最终结构，不再先建旧主键后多次 ALTER。
CREATE TABLE review_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '评审任务 ID',
    delete_status TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除状态：0未删除，1已删除；普通业务更新不得修改',
    source_type VARCHAR(64) NOT NULL COMMENT '评审来源；互检统一使用 MUTUAL_CHECK_ITEM',
    reviewer_employee_no VARCHAR(64) NOT NULL COMMENT '点击评审完成的人员工号',
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点击评审完成的时间',
    active_source_type VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN delete_status=0 THEN source_type ELSE NULL END),
    UNIQUE KEY uk_review_submission_active (task_id, active_source_type, reviewer_employee_no)
) COMMENT='评审完成记录；重新开启阶段时逻辑删除旧记录，重新完成时新增';

-- 回填旧版本已移出待办且该来源全部意见已闭环的人员；互检两个来源合并为一条完成记录。
INSERT INTO review_submission (task_id, source_type, reviewer_employee_no, submitted_at)
SELECT o.task_id,
       CASE WHEN o.source_type='MUTUAL_EXTRA' THEN 'MUTUAL_CHECK_ITEM' ELSE o.source_type END,
       o.raised_by_employee_no, MAX(o.updated_at)
FROM review_opinion o
JOIN review_task t ON t.id=o.task_id
WHERE o.delete_status=0 AND t.delete_status=0
  AND LOCATE(CONCAT(',', o.raised_by_employee_no, ','), CONCAT(',', COALESCE(t.assigned_reviewer_employee_nos, ''), ','))=0
GROUP BY o.task_id,
         CASE WHEN o.source_type='MUTUAL_EXTRA' THEN 'MUTUAL_CHECK_ITEM' ELSE o.source_type END,
         o.raised_by_employee_no
HAVING SUM(CASE WHEN o.is_draft=TRUE OR o.opinion_status NOT IN ('CONFIRMED_PASS', 'WITHDRAWN') THEN 1 ELSE 0 END)=0;

-- 4. 仅约束有效互检快照；逻辑删除后可新增快照，原记录保留。
ALTER TABLE task_check_item DROP INDEX uk_task_check_item;
ALTER TABLE task_check_item ADD COLUMN active_item_id BIGINT GENERATED ALWAYS AS (CASE WHEN delete_status=0 THEN item_id ELSE NULL END);
ALTER TABLE task_check_item ADD UNIQUE KEY uk_task_check_item (task_id, active_item_id);
