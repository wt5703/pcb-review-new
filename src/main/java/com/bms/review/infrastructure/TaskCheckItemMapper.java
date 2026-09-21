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

    @Insert("INSERT INTO task_check_item (id, task_id, template_item_id, template_item_key, parent_item_key, item_name, sort_no, status, version) "
            + "VALUES (#{id}, #{taskId}, #{templateItemId}, #{templateItemKey}, #{parentItemKey}, #{itemName}, #{sortNo}, #{status}, #{version})")
    int insert(TaskCheckItemRecord record);

    @Update("UPDATE task_check_item SET template_item_key=#{templateItemKey}, parent_item_key=#{parentItemKey}, item_name=#{itemName}, "
            + "sort_no=#{sortNo}, updated_at=CURRENT_TIMESTAMP WHERE task_id=#{taskId} AND template_item_id=#{templateItemId}")
    int refreshTemplateSnapshot(TaskCheckItemRecord record);

    @Select("SELECT id, task_id AS taskId, template_item_id AS templateItemId, template_item_key AS templateItemKey, "
            + "parent_item_key AS parentItemKey, item_name AS itemName, sort_no AS sortNo, check_result AS checkResult, comment, opinion_rich_text AS richText, "
            + "linked_opinion_id AS linkedOpinionId, status, version FROM task_check_item WHERE task_id=#{taskId} ORDER BY sort_no, id")
    List<TaskCheckItemRecord> findByTaskId(long taskId);

    @Select("SELECT id, task_id AS taskId, template_item_id AS templateItemId, template_item_key AS templateItemKey, "
            + "parent_item_key AS parentItemKey, item_name AS itemName, sort_no AS sortNo, check_result AS checkResult, comment, opinion_rich_text AS richText, "
            + "linked_opinion_id AS linkedOpinionId, status, version FROM task_check_item WHERE task_id=#{taskId} AND id=#{id}")
    TaskCheckItemRecord findByTaskIdAndId(long taskId, long id);

    @Update("UPDATE task_check_item SET check_result=#{checkResult}, comment=#{comment}, opinion_rich_text=#{richText}, linked_opinion_id=#{linkedOpinionId}, status=#{status}, "
            + "updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND task_id=#{taskId}")
    int submit(TaskCheckItemRecord record);
}
