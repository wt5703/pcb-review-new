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

    @Insert("INSERT INTO task_flow_record (id, task_id, action, action_name, operate_employee_no, comment) "
            + "VALUES (#{id}, #{taskId}, #{action}, #{actionName}, #{operateEmployeeNo}, #{comment})")
    int insert(TaskFlowRecord record);

    @Select("SELECT id, task_id AS taskId, action, action_name AS actionName, operate_employee_no AS operateEmployeeNo, comment, create_at AS createdAt "
            + "FROM task_flow_record WHERE task_id=#{taskId} ORDER BY id")
    List<TaskFlowRecord> findByTaskId(long taskId);
}
