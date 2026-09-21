package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 定义检查项附件关联的追加写入接口，同一检查项不能重复关联同一个文件。
 */
@Mapper
public interface CheckItemAttachmentMapper {
    @Insert("INSERT INTO check_item_attachment (check_item_id, file_id, sort_no) VALUES (#{checkItemId}, #{fileId}, #{sortNo})")
    int insert(CheckItemAttachmentRecord record);

    @Delete("DELETE FROM check_item_attachment WHERE check_item_id=#{checkItemId}")
    int deleteByCheckItemId(long checkItemId);

    @Select("SELECT a.file_id AS fileId, a.sort_no AS sortNo, f.file_name AS fileName, f.file_category AS fileCategory, " +
            "f.company_file_id AS previewUrl FROM check_item_attachment a JOIN review_file f ON f.id=a.file_id " +
            "WHERE a.check_item_id=#{checkItemId} ORDER BY a.sort_no, a.id")
    List<CheckItemAttachmentViewRecord> findByCheckItemId(long checkItemId);
}
