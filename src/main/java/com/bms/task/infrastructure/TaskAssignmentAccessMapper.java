package com.bms.task.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 提供任务人员访问校验。assigned_reviewer_employee_nos 只保存当前流程仍待处理人员，不能使用创建时全部预分配专家作为待办依据。
 */
@Mapper
public interface TaskAssignmentAccessMapper {
    @Select("SELECT CASE WHEN EXISTS (SELECT 1 FROM review_task WHERE id=#{taskId} AND (designer_employee_no=#{employeeNo} OR expert_leader_employee_no=#{employeeNo} "
            + "OR CONCAT(',', COALESCE(assigned_reviewer_employee_nos, ''), ',') LIKE CONCAT('%,', #{employeeNo}, ',%'))) "
            + "THEN TRUE ELSE FALSE END")
    boolean isAssignedToTask(long taskId, String employeeNo);

    @Select("SELECT CASE WHEN EXISTS (SELECT 1 FROM review_task WHERE id=#{taskId} AND status <> 'FINISHED' "
            + "AND CONCAT(',', COALESCE(assigned_reviewer_employee_nos, ''), ',') LIKE CONCAT('%,', #{employeeNo}, ',%')) "
            + "THEN TRUE ELSE FALSE END")
    boolean isCurrentTaskProcessor(long taskId, String employeeNo);
}
