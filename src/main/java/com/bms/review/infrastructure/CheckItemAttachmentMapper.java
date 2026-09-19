package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 定义检查项附件关联的追加写入接口，同一检查项不能重复关联同一个文件版本。
 */
@Mapper
public interface CheckItemAttachmentMapper {
    @Insert("INSERT INTO check_item_attachment (check_item_id, file_id, sort_no) VALUES (#{checkItemId}, #{fileId}, #{sortNo})")
    int insert(CheckItemAttachmentRecord record);
}
