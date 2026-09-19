package com.bms.identity.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 提供受限专家查看任务时所需的任务分配关系查询，仅判断用户是否已作为评审参与者关联任务，不处理评审进度或流程流转。
 */
@Mapper
public interface TaskAssignmentAccessMapper {
    @Select("SELECT CASE WHEN EXISTS (SELECT 1 FROM task_reviewer WHERE task_id = #{taskId} AND reviewer_id = #{userId} AND process_status <> 'CANCELLED') "
            + "THEN TRUE ELSE FALSE END")
    boolean isAssignedToTask(long taskId, long userId);

    @Select("SELECT CASE WHEN EXISTS (SELECT 1 FROM task_reviewer WHERE task_id = #{taskId} AND reviewer_id = #{userId} "
            + "AND process_status IN ('PENDING', 'IN_PROGRESS')) THEN TRUE ELSE FALSE END")
    boolean isCurrentTaskProcessor(long taskId, long userId);
}
