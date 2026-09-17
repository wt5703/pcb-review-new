package com.leapmotor.pcbreview.task.infrastructure;

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

    @Insert("INSERT INTO review_task (id, review_type, task_name, project_name, designer_id, design_name, pcb_type, status, initial_file_ids, version) " +
            "VALUES (#{id}, #{reviewType}, #{taskName}, #{projectName}, #{designerId}, #{designName}, #{pcbType}, #{status}, #{initialFileIds}, #{version})")
    int insert(ReviewTaskRecord record);

    @Select("SELECT id, review_type AS reviewType, task_name AS taskName, project_name AS projectName, designer_id AS designerId, " +
            "design_name AS designName, pcb_type AS pcbType, status, initial_file_ids AS initialFileIds, version FROM review_task WHERE id = #{id}")
    ReviewTaskRecord findById(long id);

    @Select("SELECT id, review_type AS reviewType, task_name AS taskName, project_name AS projectName, designer_id AS designerId, design_name AS designName, pcb_type AS pcbType, status, initial_file_ids AS initialFileIds, version FROM review_task ORDER BY id")
    List<ReviewTaskRecord> findAll();

    @Update("UPDATE review_task SET pcb_type=#{pcbType}, status=#{status}, initial_file_ids=#{initialFileIds}, version=#{version}+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND version=#{version}")
    int update(ReviewTaskRecord record);
}
