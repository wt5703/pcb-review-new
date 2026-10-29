package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.bms.review.domain.OpinionSourceType;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 定义评审意见主记录和答复、确认历史的持久化访问接口；意见列表按答复轮次聚合完整答复及其一一对应的确认记录。
 */
@Mapper
public interface ReviewOpinionMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM review_opinion")
    long nextOpinionId();

    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM opinion_reply")
    long nextReplyId();

    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM opinion_confirmation")
    long nextConfirmationId();

    @Insert("INSERT INTO review_opinion (id, task_id, source_type, source_item_id, severity, is_no_opinion, is_draft, comment, rich_text_content, raised_by_employee_no, raised_by_name, status) "
            + "VALUES (#{id}, #{taskId}, #{sourceType}, #{sourceItemId}, #{severity}, #{noOpinion}, #{draft}, #{comment}, #{richText}, #{raisedByEmployeeNo}, #{raisedByName}, #{status})")
    int insert(ReviewOpinionRecord record);

    @Select("SELECT id, task_id AS taskId, source_type AS sourceType, source_item_id AS sourceItemId, severity, is_no_opinion AS noOpinion, is_draft AS draft, comment, rich_text_content AS richText, raised_by_employee_no AS raisedByEmployeeNo, raised_by_name AS raisedByName, "
            + "status, created_at AS createdAt FROM review_opinion WHERE id=#{id}")
    ReviewOpinionRecord findById(long id);

    @Select("SELECT id, task_id AS taskId, source_type AS sourceType, source_item_id AS sourceItemId, severity, is_no_opinion AS noOpinion, is_draft AS draft, comment, rich_text_content AS richText, raised_by_employee_no AS raisedByEmployeeNo, raised_by_name AS raisedByName, "
            + "status, created_at AS createdAt, updated_at AS updatedAt FROM review_opinion WHERE task_id=#{taskId} ORDER BY id")
    List<ReviewOpinionRecord> findByTaskId(long taskId);

    /**
     * 意见列表的过滤、计数和分页均在数据库执行；无意见审计记录不进入问题列表。
     * sourceTypes 非空时优先于兼容参数 sourceType，raisedByEmployeeNo 非空时仅查询当前评审人工作台数据。
     */
    @Select("<script>"
            + "SELECT COUNT(*) FROM review_opinion "
            + "<where>"
            + "task_id=#{taskId} AND is_no_opinion=FALSE AND is_draft=FALSE "
            + "<if test='severity != null'>AND severity=#{severity} </if>"
            + "<if test='status != null and status != \"\"'>AND status=#{status} </if>"
            + "<choose>"
            + "<when test='sourceTypes != null and sourceTypes.size() > 0'>"
            + "AND source_type IN <foreach collection='sourceTypes' item='sourceType' open='(' separator=',' close=')'>#{sourceType}</foreach> "
            + "</when>"
            + "<when test='sourceType != null'>AND source_type=#{sourceType} </when>"
            + "</choose>"
            + "<if test='raisedByEmployeeNo != null and raisedByEmployeeNo != \"\"'>AND raised_by_employee_no=#{raisedByEmployeeNo} </if>"
            + "</where>"
            + "</script>")
    long countPage(@Param("taskId") long taskId, @Param("severity") com.bms.review.domain.OpinionSeverity severity,
                   @Param("status") String status, @Param("sourceType") OpinionSourceType sourceType,
                   @Param("sourceTypes") List<OpinionSourceType> sourceTypes,
                   @Param("raisedByEmployeeNo") String raisedByEmployeeNo);

    @Select("<script>"
            + "SELECT id, task_id AS taskId, source_type AS sourceType, source_item_id AS sourceItemId, severity, is_no_opinion AS noOpinion, is_draft AS draft, comment, rich_text_content AS richText, raised_by_employee_no AS raisedByEmployeeNo, raised_by_name AS raisedByName, "
            + "status, created_at AS createdAt FROM review_opinion "
            + "<where>"
            + "task_id=#{taskId} AND is_no_opinion=FALSE AND is_draft=FALSE "
            + "<if test='severity != null'>AND severity=#{severity} </if>"
            + "<if test='status != null and status != \"\"'>AND status=#{status} </if>"
            + "<choose>"
            + "<when test='sourceTypes != null and sourceTypes.size() > 0'>"
            + "AND source_type IN <foreach collection='sourceTypes' item='sourceType' open='(' separator=',' close=')'>#{sourceType}</foreach> "
            + "</when>"
            + "<when test='sourceType != null'>AND source_type=#{sourceType} </when>"
            + "</choose>"
            + "<if test='raisedByEmployeeNo != null and raisedByEmployeeNo != \"\"'>AND raised_by_employee_no=#{raisedByEmployeeNo} </if>"
            + "</where>"
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<ReviewOpinionRecord> findPage(@Param("taskId") long taskId,
                                       @Param("severity") com.bms.review.domain.OpinionSeverity severity,
                                       @Param("status") String status, @Param("sourceType") OpinionSourceType sourceType,
                                       @Param("sourceTypes") List<OpinionSourceType> sourceTypes,
                                       @Param("raisedByEmployeeNo") String raisedByEmployeeNo,
                                       @Param("offset") long offset, @Param("limit") int limit);

    @Select("SELECT id, task_id AS taskId, source_type AS sourceType, source_item_id AS sourceItemId, severity, is_no_opinion AS noOpinion, is_draft AS draft, comment, rich_text_content AS richText, raised_by_employee_no AS raisedByEmployeeNo, raised_by_name AS raisedByName, " +
            "status, created_at AS createdAt FROM review_opinion " +
            "WHERE task_id=#{taskId} AND source_type=#{sourceType} AND source_item_id=#{sourceItemId} AND status <> 'WITHDRAWN' ORDER BY id DESC LIMIT 1")
    ReviewOpinionRecord findActiveMutualCheckItemOpinion(long taskId, long sourceItemId, OpinionSourceType sourceType);

    @Select("SELECT status FROM review_opinion WHERE task_id=#{taskId} AND raised_by_employee_no=#{raisedByEmployeeNo} ORDER BY id")
    List<String> findStatusesByTaskAndRaisedByEmployeeNo(long taskId, String raisedByEmployeeNo);

    @Select("SELECT DISTINCT task_id FROM review_opinion WHERE status='PENDING_REPLY' AND is_draft=FALSE AND task_id IN "
            + "(SELECT id FROM review_task WHERE designer_employee_no=#{designerEmployeeNo})")
    List<Long> findPendingReplyTaskIdsForDesignerEmployeeNo(String designerEmployeeNo);

    @Select("SELECT DISTINCT task_id FROM review_opinion WHERE status='PENDING_CONFIRMATION' AND is_draft=FALSE AND raised_by_employee_no=#{raisedByEmployeeNo}")
    List<Long> findPendingConfirmationTaskIdsForRaiserEmployeeNo(String raisedByEmployeeNo);

    @Update("UPDATE review_opinion SET status=#{status}, updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int updateStatus(ReviewOpinionRecord record);

    @Select("SELECT EXISTS(SELECT 1 FROM opinion_reply WHERE opinion_id=#{opinionId})")
    boolean existsReply(long opinionId);

    /**
     * 编辑只允许发生在尚未产生任何设计者答复的待答复状态；SQL 条件同时避免“检查后再写入”的并发窗口。
     */
    @Update("UPDATE review_opinion SET severity=#{severity}, comment=#{comment}, rich_text_content=#{richText}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status='PENDING_REPLY' AND NOT EXISTS (SELECT 1 FROM opinion_reply WHERE opinion_id=#{id})")
    int updateUnrepliedOpinion(ReviewOpinionRecord record);

    /** 互检固定项维护同源意见时使用；其状态控制由互检业务本身负责。 */
    @Update("UPDATE review_opinion SET severity=#{severity}, comment=#{comment}, rich_text_content=#{richText}, is_draft=#{draft}, updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int updateOpinion(ReviewOpinionRecord record);

    @Delete("DELETE FROM review_opinion WHERE id=#{id} AND status='PENDING_REPLY'")
    int deletePendingReply(long id);

    @Insert("INSERT INTO opinion_reply (id, opinion_id, reply_type, reason, replied_by_employee_no, reply_no) "
            + "VALUES (#{id}, #{opinionId}, #{replyType}, #{reason}, #{repliedByEmployeeNo}, #{replyNo})")
    int insertReply(OpinionReplyRecord record);

    @Select("SELECT id, opinion_id AS opinionId, reply_type AS replyType, reason, replied_by_employee_no AS repliedByEmployeeNo, reply_no AS replyNo, created_at AS createdAt "
            + "FROM opinion_reply WHERE opinion_id=#{opinionId} ORDER BY reply_no DESC LIMIT 1")
    OpinionReplyRecord findLatestReply(long opinionId);

    @Select("SELECT id, opinion_id AS opinionId, reply_type AS replyType, reason, replied_by_employee_no AS repliedByEmployeeNo, reply_no AS replyNo, created_at AS createdAt "
            + "FROM opinion_reply WHERE opinion_id=#{opinionId} ORDER BY reply_no")
    List<OpinionReplyRecord> findRepliesByOpinionId(long opinionId);

    @Insert("INSERT INTO opinion_confirmation (id, opinion_id, reply_id, passed, comment, confirmed_by_employee_no) "
            + "VALUES (#{id}, #{opinionId}, #{replyId}, #{passed}, #{comment}, #{confirmedByEmployeeNo})")
    int insertConfirmation(OpinionConfirmationRecord record);

    @Select("SELECT id, opinion_id AS opinionId, reply_id AS replyId, passed, comment, confirmed_by_employee_no AS confirmedByEmployeeNo, created_at AS createdAt "
            + "FROM opinion_confirmation WHERE opinion_id=#{opinionId} ORDER BY id")
    List<OpinionConfirmationRecord> findConfirmationsByOpinionId(long opinionId);
}
