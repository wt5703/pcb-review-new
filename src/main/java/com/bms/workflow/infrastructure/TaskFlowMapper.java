package com.bms.workflow.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 定义任务提交和流程推进记录的持久化接口，仅追加不可变历史，不提供删除或修改能力。
 */
@Mapper
public interface TaskFlowMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM task_flow_record")
    long nextId();

    @Insert("INSERT INTO task_flow_record (id, task_id, action_code, action_name, operate_employee_no, flow_comment, from_status, to_status, next_operator_employee_nos, approval_role) "
            + "VALUES (#{id}, #{taskId}, #{action}, #{actionName}, #{operateEmployeeNo}, #{comment}, #{fromStatus}, #{toStatus}, #{nextOperatorEmployeeNos}, #{approvalRole})")
    int insert(TaskFlowRecord record);

    @Select("SELECT id, task_id AS taskId, action_code AS action, action_name AS actionName, operate_employee_no AS operateEmployeeNo, flow_comment AS comment, "
            + "from_status AS fromStatus, to_status AS toStatus, next_operator_employee_nos AS nextOperatorEmployeeNos, approval_role AS approvalRole, create_at AS createdAt "
            + "FROM task_flow_record WHERE task_id=#{taskId} ORDER BY id")
    List<TaskFlowRecord> findByTaskId(long taskId);
}
