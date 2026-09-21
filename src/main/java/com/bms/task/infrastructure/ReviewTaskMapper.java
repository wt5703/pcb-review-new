package com.bms.task.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Insert("INSERT INTO review_task (id, review_type, task_name, project_name, designer_id, designer_name, design_name, pcb_type, " +
            "expected_completed_date, expert_leader_id, expert_leader_name, review_roles, review_description, status, initial_file_ids, version) " +
            "VALUES (#{id}, #{reviewType}, #{taskName}, #{projectName}, #{designerId}, COALESCE(#{designerName}, CONCAT('设计者#', #{designerId})), #{designName}, #{pcbType}, " +
            "#{expectedCompletedDate}, #{expertLeaderId}, #{expertLeaderName}, COALESCE(#{reviewRoles}, ''), #{reviewDescription}, #{status}, #{initialFileIds}, #{version})")
    int insert(ReviewTaskRecord record);

    @Select("SELECT id, review_type AS reviewType, task_name AS taskName, project_name AS projectName, designer_id AS designerId, " +
            "designer_name AS designerName, design_name AS designName, pcb_type AS pcbType, expected_completed_date AS expectedCompletedDate, " +
            "expert_leader_id AS expertLeaderId, expert_leader_name AS expertLeaderName, review_roles AS reviewRoles, review_description AS reviewDescription, " +
            "status, initial_file_ids AS initialFileIds, version FROM review_task WHERE id = #{id}")
    ReviewTaskRecord findById(long id);

    @Select("SELECT id, review_type AS reviewType, task_name AS taskName, project_name AS projectName, designer_id AS designerId, " +
            "designer_name AS designerName, design_name AS designName, pcb_type AS pcbType, expected_completed_date AS expectedCompletedDate, " +
            "expert_leader_id AS expertLeaderId, expert_leader_name AS expertLeaderName, review_roles AS reviewRoles, review_description AS reviewDescription, " +
            "status, initial_file_ids AS initialFileIds, version FROM review_task ORDER BY id")
    List<ReviewTaskRecord> findAll();

    @Update("UPDATE review_task SET pcb_type=#{pcbType}, status=#{status}, initial_file_ids=#{initialFileIds}, updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int update(ReviewTaskRecord record);

    @Update("UPDATE review_task SET review_type=#{reviewType}, task_name=#{taskName}, project_name=#{projectName}, designer_name=#{designerName}, "
            + "design_name=#{designName}, pcb_type=#{pcbType}, expected_completed_date=#{expectedCompletedDate}, expert_leader_id=#{expertLeaderId}, "
            + "expert_leader_name=#{expertLeaderName}, review_roles=#{reviewRoles}, review_description=#{reviewDescription}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status='DRAFT'")
    int updateDraft(ReviewTaskRecord record);
}
