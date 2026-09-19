package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 定义评审意见主记录和答复、确认历史的持久化访问接口，所有状态更新均使用版本号避免并发覆盖意见闭环结果。
 */
@Mapper
public interface ReviewOpinionMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM review_opinion")
    long nextOpinionId();

    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM opinion_reply")
    long nextReplyId();

    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM opinion_confirmation")
    long nextConfirmationId();

    @Insert("INSERT INTO review_opinion (id, task_id, source_type, source_item_id, severity, content, raised_by, file_version_id, status, version) "
            + "VALUES (#{id}, #{taskId}, #{sourceType}, #{sourceItemId}, #{severity}, #{content}, #{raisedBy}, #{fileVersionId}, #{status}, #{version})")
    int insert(ReviewOpinionRecord record);

    @Select("SELECT id, task_id AS taskId, source_type AS sourceType, source_item_id AS sourceItemId, severity, content, raised_by AS raisedBy, "
            + "file_version_id AS fileVersionId, status, version, created_at AS createdAt FROM review_opinion WHERE id=#{id}")
    ReviewOpinionRecord findById(long id);

    @Select("SELECT id, task_id AS taskId, source_type AS sourceType, source_item_id AS sourceItemId, severity, content, raised_by AS raisedBy, "
            + "file_version_id AS fileVersionId, status, version, created_at AS createdAt FROM review_opinion WHERE task_id=#{taskId} ORDER BY id")
    List<ReviewOpinionRecord> findByTaskId(long taskId);

    @Select("SELECT status FROM review_opinion WHERE task_id=#{taskId} AND raised_by=#{raisedBy} ORDER BY id")
    List<String> findStatusesByTaskAndRaisedBy(long taskId, long raisedBy);

    @Select("SELECT DISTINCT task_id FROM review_opinion WHERE status='PENDING_REPLY' AND task_id IN "
            + "(SELECT id FROM review_task WHERE designer_id=#{designerId})")
    List<Long> findPendingReplyTaskIdsForDesigner(long designerId);

    @Select("SELECT DISTINCT task_id FROM review_opinion WHERE status='PENDING_CONFIRMATION' AND raised_by=#{raisedBy}")
    List<Long> findPendingConfirmationTaskIdsForRaiser(long raisedBy);

    @Update("UPDATE review_opinion SET status=#{status}, updated_at=CURRENT_TIMESTAMP, version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateStatus(ReviewOpinionRecord record);

    @Insert("INSERT INTO opinion_reply (id, opinion_id, reply_type, reason, file_version_id, replied_by, reply_no) "
            + "VALUES (#{id}, #{opinionId}, #{replyType}, #{reason}, #{fileVersionId}, #{repliedBy}, #{replyNo})")
    int insertReply(OpinionReplyRecord record);

    @Select("SELECT id, opinion_id AS opinionId, reply_type AS replyType, reason, file_version_id AS fileVersionId, replied_by AS repliedBy, reply_no AS replyNo, created_at AS createdAt "
            + "FROM opinion_reply WHERE opinion_id=#{opinionId} ORDER BY reply_no DESC LIMIT 1")
    OpinionReplyRecord findLatestReply(long opinionId);

    @Select("SELECT id, opinion_id AS opinionId, reply_type AS replyType, reason, file_version_id AS fileVersionId, replied_by AS repliedBy, reply_no AS replyNo, created_at AS createdAt "
            + "FROM opinion_reply WHERE opinion_id=#{opinionId} ORDER BY reply_no")
    List<OpinionReplyRecord> findRepliesByOpinionId(long opinionId);

    @Insert("INSERT INTO opinion_confirmation (id, opinion_id, reply_id, passed, comment, confirmed_by) "
            + "VALUES (#{id}, #{opinionId}, #{replyId}, #{passed}, #{comment}, #{confirmedBy})")
    int insertConfirmation(OpinionConfirmationRecord record);

    @Select("SELECT id, opinion_id AS opinionId, reply_id AS replyId, passed, comment, confirmed_by AS confirmedBy, created_at AS createdAt "
            + "FROM opinion_confirmation WHERE opinion_id=#{opinionId} ORDER BY id")
    List<OpinionConfirmationRecord> findConfirmationsByOpinionId(long opinionId);
}
