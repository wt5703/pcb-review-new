-- 已有数据库部署本次版本前执行一次；全新数据库使用 ddl.sql，无需再执行本脚本。
CREATE TABLE review_submission (
    task_id BIGINT NOT NULL COMMENT '评审任务 ID',
    source_type VARCHAR(64) NOT NULL COMMENT '评审来源；互检统一使用 MUTUAL_CHECK_ITEM',
    reviewer_employee_no VARCHAR(64) NOT NULL COMMENT '点击评审完成的人员工号',
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点击评审完成的时间',
    PRIMARY KEY (task_id, source_type, reviewer_employee_no)
) COMMENT='当前轮次评审完成记录；重新开启对应阶段时清除，意见历史保留';

-- 兼容旧版本已通过确认提交而移出待办的人员；仍在待办中的人必须重新明确点击评审完成。
-- 仅回填该来源全部意见已闭环的人员，不用单条通过意见代替整个来源完成。
INSERT INTO review_submission (task_id, source_type, reviewer_employee_no, submitted_at)
SELECT o.task_id,
       CASE WHEN o.source_type = 'MUTUAL_EXTRA' THEN 'MUTUAL_CHECK_ITEM' ELSE o.source_type END,
       o.raised_by_employee_no, MAX(o.updated_at)
FROM review_opinion o
JOIN review_task t ON t.id = o.task_id
WHERE o.is_draft = FALSE
  AND o.opinion_status IN ('CONFIRMED_PASS', 'WITHDRAWN')
  AND LOCATE(CONCAT(',', o.raised_by_employee_no, ','),
             CONCAT(',', COALESCE(t.assigned_reviewer_employee_nos, ''), ',')) = 0
  AND NOT EXISTS (
      SELECT 1 FROM review_opinion pending
      WHERE pending.task_id = o.task_id AND pending.raised_by_employee_no = o.raised_by_employee_no
        AND (CASE WHEN pending.source_type = 'MUTUAL_EXTRA' THEN 'MUTUAL_CHECK_ITEM' ELSE pending.source_type END)
            = (CASE WHEN o.source_type = 'MUTUAL_EXTRA' THEN 'MUTUAL_CHECK_ITEM' ELSE o.source_type END)
        AND (pending.is_draft = TRUE OR pending.opinion_status NOT IN ('CONFIRMED_PASS', 'WITHDRAWN'))
  )
GROUP BY o.task_id,
         CASE WHEN o.source_type = 'MUTUAL_EXTRA' THEN 'MUTUAL_CHECK_ITEM' ELSE o.source_type END,
         o.raised_by_employee_no;
