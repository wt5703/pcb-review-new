package com.leapmotor.pcbreview.workflow.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}
