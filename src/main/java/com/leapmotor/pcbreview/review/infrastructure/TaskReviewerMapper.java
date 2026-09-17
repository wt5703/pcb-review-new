package com.leapmotor.pcbreview.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 定义 task_reviewer 表的人员分配和处理进度访问接口，保留已取消改派记录以支持审计追溯。
 */
@Mapper
public interface TaskReviewerMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM task_reviewer")
    long nextId();

    @Insert("INSERT INTO task_reviewer (id, task_id, review_role, reviewer_id, process_status, assigned_by, no_opinion, version) "
            + "VALUES (#{id}, #{taskId}, #{reviewRole}, #{reviewerId}, #{processStatus}, #{assignedBy}, #{noOpinion}, #{version})")
    int insert(TaskReviewerRecord record);

    @Select("SELECT id, task_id AS taskId, review_role AS reviewRole, reviewer_id AS reviewerId, process_status AS processStatus, "
            + "assigned_by AS assignedBy, no_opinion AS noOpinion, version FROM task_reviewer WHERE task_id=#{taskId} AND review_role=#{reviewRole} "
            + "AND process_status <> 'CANCELLED' ORDER BY id")
    List<TaskReviewerRecord> findActiveByTaskAndRole(long taskId, String reviewRole);

    @Select("SELECT id, task_id AS taskId, review_role AS reviewRole, reviewer_id AS reviewerId, process_status AS processStatus, "
            + "assigned_by AS assignedBy, no_opinion AS noOpinion, version FROM task_reviewer WHERE task_id=#{taskId} "
            + "AND process_status <> 'CANCELLED' ORDER BY review_role, id")
    List<TaskReviewerRecord> findActiveByTaskId(long taskId);

    @Update("UPDATE task_reviewer SET process_status='CANCELLED', version=version+1 WHERE task_id=#{taskId} AND review_role=#{reviewRole} "
            + "AND process_status IN ('PENDING', 'IN_PROGRESS')")
    int cancelActive(long taskId, String reviewRole);

    @Update("UPDATE task_reviewer SET process_status=#{processStatus}, no_opinion=#{noOpinion}, submitted_at=CURRENT_TIMESTAMP, version=version+1 "
            + "WHERE task_id=#{taskId} AND reviewer_id=#{reviewerId} AND process_status IN ('PENDING', 'IN_PROGRESS')")
    int submit(long taskId, long reviewerId, String processStatus, boolean noOpinion);
}
