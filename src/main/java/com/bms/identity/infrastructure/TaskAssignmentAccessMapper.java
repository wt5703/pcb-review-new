package com.bms.identity.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 提供任务人员访问校验。任务设计者和任务指定的专家/组长可查看任务；当前处理人由任务指定的专家/组长判断。
 */
@Mapper
public interface TaskAssignmentAccessMapper {
    @Select("SELECT CASE WHEN EXISTS (SELECT 1 FROM review_task WHERE id=#{taskId} AND (designer_id=#{userId} OR expert_leader_id=#{userId} "
            + "OR CONCAT(',', COALESCE(assigned_reviewer_ids, ''), ',') LIKE CONCAT('%,', #{userId}, ',%'))) "
            + "THEN TRUE ELSE FALSE END")
    boolean isAssignedToTask(long taskId, long userId);

    @Select("SELECT CASE WHEN EXISTS (SELECT 1 FROM review_task WHERE id=#{taskId} AND expert_leader_id=#{userId} "
            + "AND status <> 'FINISHED') THEN TRUE ELSE FALSE END")
    boolean isCurrentTaskProcessor(long taskId, long userId);
}
