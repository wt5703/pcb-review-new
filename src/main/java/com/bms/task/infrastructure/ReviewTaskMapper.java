package com.bms.task.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 定义 review_task 表的 MyBatis 数据访问契约，提供任务新增、更新、按标识查询及列表读取能力，不包含业务授权规则。
 */
@Mapper
public interface ReviewTaskMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM review_task")
    long nextId();

    @Insert("INSERT INTO review_task (id, review_type, task_name, project_no, project_name, designer_employee_no, designer_name, design_name, pcb_type, " +
            "expected_completed_date, expert_leader_employee_no, expert_leader_name, review_roles, reviewer_assignments, assigned_reviewer_employee_nos, mutual_check_reviewer_employee_nos, review_description, status, initial_file_ids, version) " +
            "VALUES (#{id}, #{reviewType}, #{taskName}, COALESCE(#{projectNo}, ''), #{projectName}, #{designerEmployeeNo}, COALESCE(#{designerName}, CONCAT('设计者#', #{designerEmployeeNo})), #{designName}, #{pcbType}, " +
            "#{expectedCompletedDate}, #{expertLeaderEmployeeNo}, #{expertLeaderName}, COALESCE(#{reviewRoles}, ''), COALESCE(#{reviewerAssignments}, ''), COALESCE(#{assignedReviewerEmployeeNos}, ''), COALESCE(#{mutualCheckReviewerEmployeeNos}, ''), #{reviewDescription}, #{status}, #{initialFileIds}, #{version})")
    int insert(ReviewTaskRecord record);

    @Select("SELECT id, review_type AS reviewType, task_name AS taskName, project_no AS projectNo, project_name AS projectName, designer_employee_no AS designerEmployeeNo, " +
            "designer_name AS designerName, design_name AS designName, pcb_type AS pcbType, expected_completed_date AS expectedCompletedDate, " +
            "expert_leader_employee_no AS expertLeaderEmployeeNo, expert_leader_name AS expertLeaderName, review_roles AS reviewRoles, reviewer_assignments AS reviewerAssignments, assigned_reviewer_employee_nos AS assignedReviewerEmployeeNos, mutual_check_reviewer_employee_nos AS mutualCheckReviewerEmployeeNos, review_description AS reviewDescription, " +
            "status, initial_file_ids AS initialFileIds, version, created_at AS createdAt FROM review_task WHERE id = #{id}")
    ReviewTaskRecord findById(long id);

    @Select("SELECT id, review_type AS reviewType, task_name AS taskName, project_no AS projectNo, project_name AS projectName, designer_employee_no AS designerEmployeeNo, " +
            "designer_name AS designerName, design_name AS designName, pcb_type AS pcbType, expected_completed_date AS expectedCompletedDate, " +
            "expert_leader_employee_no AS expertLeaderEmployeeNo, expert_leader_name AS expertLeaderName, review_roles AS reviewRoles, reviewer_assignments AS reviewerAssignments, assigned_reviewer_employee_nos AS assignedReviewerEmployeeNos, mutual_check_reviewer_employee_nos AS mutualCheckReviewerEmployeeNos, review_description AS reviewDescription, " +
            "status, initial_file_ids AS initialFileIds, version FROM review_task ORDER BY id")
    List<ReviewTaskRecord> findAll();

    /** 任务列表筛选和分页均在数据库执行；列表查询不按当前用户做数据隔离。 */
    @Select("<script>"
            + "SELECT COUNT(*) FROM review_task "
            + "<where>"
            + "<if test='taskName != null and taskName != \"\"'>AND task_name LIKE CONCAT('%', #{taskName}, '%') </if>"
            + "<if test='projectNo != null and projectNo != \"\"'>AND project_no LIKE CONCAT('%', #{projectNo}, '%') </if>"
            + "<if test='projectName != null and projectName != \"\"'>AND project_name LIKE CONCAT('%', #{projectName}, '%') </if>"
            + "<if test='designerEmployeeNo != null and designerEmployeeNo != \"\"'>AND designer_employee_no=#{designerEmployeeNo} </if>"
            + "<if test='reviewType != null and reviewType != \"\"'>AND review_type=#{reviewType} </if>"
            + "<if test='statuses != null and statuses.size() > 0'>"
            + "AND status IN <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach> "
            + "</if>"
            + "<if test='taskId != null'>AND id=#{taskId} </if>"
            + "</where>"
            + "</script>")
    long countPage(@Param("taskName") String taskName, @Param("projectNo") String projectNo, @Param("projectName") String projectName,
                   @Param("designerEmployeeNo") String designerEmployeeNo, @Param("reviewType") String reviewType,
                   @Param("statuses") List<String> statuses, @Param("taskId") Long taskId);

    @Select("<script>"
            + "SELECT id, review_type AS reviewType, task_name AS taskName, project_no AS projectNo, project_name AS projectName, designer_employee_no AS designerEmployeeNo, "
            + "designer_name AS designerName, design_name AS designName, pcb_type AS pcbType, expected_completed_date AS expectedCompletedDate, "
            + "expert_leader_employee_no AS expertLeaderEmployeeNo, expert_leader_name AS expertLeaderName, review_roles AS reviewRoles, reviewer_assignments AS reviewerAssignments, "
            + "assigned_reviewer_employee_nos AS assignedReviewerEmployeeNos, mutual_check_reviewer_employee_nos AS mutualCheckReviewerEmployeeNos, review_description AS reviewDescription, status, initial_file_ids AS initialFileIds, version, created_at AS createdAt "
            + "FROM review_task "
            + "<where>"
            + "<if test='taskName != null and taskName != \"\"'>AND task_name LIKE CONCAT('%', #{taskName}, '%') </if>"
            + "<if test='projectNo != null and projectNo != \"\"'>AND project_no LIKE CONCAT('%', #{projectNo}, '%') </if>"
            + "<if test='projectName != null and projectName != \"\"'>AND project_name LIKE CONCAT('%', #{projectName}, '%') </if>"
            + "<if test='designerEmployeeNo != null and designerEmployeeNo != \"\"'>AND designer_employee_no=#{designerEmployeeNo} </if>"
            + "<if test='reviewType != null and reviewType != \"\"'>AND review_type=#{reviewType} </if>"
            + "<if test='statuses != null and statuses.size() > 0'>"
            + "AND status IN <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach> "
            + "</if>"
            + "<if test='taskId != null'>AND id=#{taskId} </if>"
            + "</where>"
            + "ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<ReviewTaskRecord> findPage(@Param("taskName") String taskName, @Param("projectNo") String projectNo, @Param("projectName") String projectName,
                                    @Param("designerEmployeeNo") String designerEmployeeNo, @Param("reviewType") String reviewType,
                                    @Param("statuses") List<String> statuses, @Param("taskId") Long taskId,
                                    @Param("offset") long offset, @Param("limit") int limit);

    /** 我的待办只需要处理中任务，避免将已结束任务加载到应用层再过滤。 */
    @Select("SELECT id, review_type AS reviewType, task_name AS taskName, project_no AS projectNo, project_name AS projectName, designer_employee_no AS designerEmployeeNo, "
            + "designer_name AS designerName, design_name AS designName, pcb_type AS pcbType, expected_completed_date AS expectedCompletedDate, "
            + "expert_leader_employee_no AS expertLeaderEmployeeNo, expert_leader_name AS expertLeaderName, review_roles AS reviewRoles, reviewer_assignments AS reviewerAssignments, assigned_reviewer_employee_nos AS assignedReviewerEmployeeNos, mutual_check_reviewer_employee_nos AS mutualCheckReviewerEmployeeNos, review_description AS reviewDescription, "
            + "status, initial_file_ids AS initialFileIds, version, created_at AS createdAt FROM review_task WHERE status <> 'FINISHED' ORDER BY id DESC")
    List<ReviewTaskRecord> findAllUnfinished();

    /** 工作台“本月已结束”仅统计当前设计者创建，并在指定自然月内结束的任务。 */
    @Select("SELECT COUNT(*) FROM review_task WHERE designer_employee_no=#{designerEmployeeNo} AND status='FINISHED' "
            + "AND updated_at >= #{monthStart} AND updated_at < #{nextMonthStart}")
    long countFinishedByDesignerEmployeeNoInPeriod(@Param("designerEmployeeNo") String designerEmployeeNo,
                                                   @Param("monthStart") LocalDateTime monthStart,
                                                   @Param("nextMonthStart") LocalDateTime nextMonthStart);

    /** 提交任务或保存初始文件时，同步保存角色—专家分配，供邮件通知等后续流程读取。 */
    @Update("UPDATE review_task SET pcb_type=#{pcbType}, review_roles=#{reviewRoles}, reviewer_assignments=#{reviewerAssignments}, "
            + "status=#{status}, initial_file_ids=#{initialFileIds}, updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int update(ReviewTaskRecord record);

    /** 取消初始文件上传时，同步去掉任务中冗余保存的文件主键。 */
    @Update("UPDATE review_task SET initial_file_ids=TRIM(BOTH ',' FROM REPLACE(CONCAT(',', COALESCE(initial_file_ids, ''), ','), "
            + "CONCAT(',', #{fileRecordId}, ','), ',')), updated_at=CURRENT_TIMESTAMP WHERE id=#{taskId}")
    int removeInitialFileId(@Param("taskId") long taskId, @Param("fileRecordId") long fileRecordId);

    /** 仅维护当前流程节点待处理人员，不覆盖创建任务时保存的 reviewer_assignments。 */
    @Update("UPDATE review_task SET assigned_reviewer_employee_nos=#{assignedReviewerEmployeeNos}, " +
            "mutual_check_reviewer_employee_nos=COALESCE(#{mutualCheckReviewerEmployeeNos}, mutual_check_reviewer_employee_nos), " +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int updateAssignedReviewerEmployeeNos(ReviewTaskRecord record);

    @Update("UPDATE review_task SET review_type=#{reviewType}, task_name=#{taskName}, project_no=#{projectNo}, project_name=#{projectName}, designer_name=#{designerName}, "
            + "design_name=#{designName}, pcb_type=#{pcbType}, expected_completed_date=#{expectedCompletedDate}, expert_leader_employee_no=#{expertLeaderEmployeeNo}, "
            + "expert_leader_name=#{expertLeaderName}, review_roles=#{reviewRoles}, reviewer_assignments=#{reviewerAssignments}, assigned_reviewer_employee_nos=#{assignedReviewerEmployeeNos}, mutual_check_reviewer_employee_nos=COALESCE(#{mutualCheckReviewerEmployeeNos}, mutual_check_reviewer_employee_nos), review_description=#{reviewDescription}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status='DRAFT'")
    int updateDraft(ReviewTaskRecord record);
}
