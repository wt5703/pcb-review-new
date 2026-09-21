package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 定义检查项模板的最小持久化访问契约，当前本地 Mock 阶段支持录入和按任务类型读取已启用模板。
 */
@Mapper
public interface CheckItemTemplateMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM check_item_template")
    long nextId();

    @Insert("INSERT INTO check_item_template (id, review_type, item_key, parent_item_key, item_name, sort_no, enabled, version) "
            + "VALUES (#{id}, #{reviewType}, #{itemKey}, #{parentItemKey}, #{itemName}, #{sortNo}, #{enabled}, #{version})")
    int insert(CheckItemTemplateRecord record);

    @Select("SELECT id, review_type AS reviewType, item_key AS itemKey, parent_item_key AS parentItemKey, item_name AS itemName, "
            + "sort_no AS sortNo, enabled, version FROM check_item_template WHERE review_type=#{reviewType} AND enabled=TRUE ORDER BY sort_no, id")
    List<CheckItemTemplateRecord> findEnabledByReviewType(String reviewType);

    @Select("SELECT id, review_type AS reviewType, item_key AS itemKey, parent_item_key AS parentItemKey, item_name AS itemName, "
            + "sort_no AS sortNo, enabled, version FROM check_item_template WHERE (#{reviewType} IS NULL OR review_type=#{reviewType}) ORDER BY review_type, sort_no, id")
    List<CheckItemTemplateRecord> findAll(String reviewType);

    @Select("SELECT id, review_type AS reviewType, item_key AS itemKey, parent_item_key AS parentItemKey, item_name AS itemName, "
            + "sort_no AS sortNo, enabled, version FROM check_item_template WHERE id=#{id}")
    CheckItemTemplateRecord findById(long id);

    @Select("SELECT id, review_type AS reviewType, item_key AS itemKey, parent_item_key AS parentItemKey, item_name AS itemName, "
            + "sort_no AS sortNo, enabled, version FROM check_item_template WHERE review_type=#{reviewType} AND item_key=#{itemKey}")
    CheckItemTemplateRecord findByReviewTypeAndItemKey(String reviewType, String itemKey);

    @Update("UPDATE check_item_template SET item_key=#{itemKey}, parent_item_key=#{parentItemKey}, item_name=#{itemName}, sort_no=#{sortNo}, "
            + "enabled=#{enabled}, updated_at=CURRENT_TIMESTAMP, version=version+1 WHERE id=#{id} AND version=#{version}")
    int update(CheckItemTemplateRecord record);

    @Update("UPDATE check_item_template SET enabled=FALSE, updated_at=CURRENT_TIMESTAMP, version=version+1 WHERE parent_item_key=#{parentItemKey}")
    int disableChildrenByParentItemKey(String parentItemKey);

    @Update("UPDATE check_item_template SET enabled=FALSE, updated_at=CURRENT_TIMESTAMP, version=version+1 WHERE id=#{id}")
    int disableById(long id);
}
