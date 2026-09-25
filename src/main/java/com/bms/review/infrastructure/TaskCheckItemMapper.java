package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 定义任务内检查项实例的数据访问接口，支持模板同步后的新增、显示字段刷新、任务查询和检查结论提交。
 */
@Mapper
public interface TaskCheckItemMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM task_check_item")
    long nextId();

    @Insert("INSERT INTO task_check_item (id, task_id, item_id, parent_id, sort_no, status) "
            + "VALUES (#{id}, #{taskId}, #{itemId}, #{parentId}, #{sortNo}, #{status})")
    int insert(TaskCheckItemRecord record);

    @Update("UPDATE task_check_item SET parent_id=#{parentId}, sort_no=#{sortNo}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE task_id=#{taskId} AND item_id=#{itemId}")
    int refreshTemplateSnapshot(TaskCheckItemRecord record);

    @Select("SELECT id, task_id AS taskId, item_id AS itemId, parent_id AS parentId, sort_no AS sortNo, "
            + "check_result AS checkResult, comment, rich_text AS richText, status FROM task_check_item WHERE task_id=#{taskId} ORDER BY sort_no, id")
    List<TaskCheckItemRecord> findByTaskId(long taskId);

    @Select("SELECT id, task_id AS taskId, item_id AS itemId, parent_id AS parentId, sort_no AS sortNo, "
            + "check_result AS checkResult, comment, rich_text AS richText, status FROM task_check_item WHERE task_id=#{taskId} AND id=#{id}")
    TaskCheckItemRecord findByTaskIdAndId(long taskId, long id);

    @Update("UPDATE task_check_item SET check_result=#{checkResult}, comment=#{comment}, rich_text=#{richText}, status=#{status}, "
            + "updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND task_id=#{taskId}")
    int submit(TaskCheckItemRecord record);
}
