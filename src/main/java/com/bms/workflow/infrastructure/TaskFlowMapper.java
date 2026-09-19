package com.bms.workflow.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 定义任务流程流转记录的持久化接口，仅追加不可变历史，不提供删除或修改能力。
 */
@Mapper
public interface TaskFlowMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM task_flow_record")
    long nextId();

    @Insert("INSERT INTO task_flow_record (id, task_id, from_status, to_status, action, operator_id, comment) "
            + "VALUES (#{id}, #{taskId}, #{fromStatus}, #{toStatus}, #{action}, #{operatorId}, #{comment})")
    int insert(TaskFlowRecord record);

    @Select("SELECT id, task_id AS taskId, from_status AS fromStatus, to_status AS toStatus, action, operator_id AS operatorId, comment, created_at AS createdAt "
            + "FROM task_flow_record WHERE task_id=#{taskId} ORDER BY id")
    List<TaskFlowRecord> findByTaskId(long taskId);
}
