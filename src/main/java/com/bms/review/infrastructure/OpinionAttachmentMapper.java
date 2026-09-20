package com.bms.review.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 定义专家评审意见图片关联的持久化读写操作，返回信息仅用于列表缩略图及文件下载入口。
 */
@Mapper
public interface OpinionAttachmentMapper {
    @Insert("INSERT INTO opinion_attachment (opinion_id, file_id, sort_no) VALUES (#{opinionId}, #{fileId}, #{sortNo})")
    int insert(OpinionAttachmentRecord record);

    @Select("SELECT a.file_id AS fileId, a.sort_no AS sortNo, f.file_name AS fileName, f.file_category AS fileCategory, " +
            "f.company_file_id AS previewUrl FROM opinion_attachment a JOIN review_file f ON f.id=a.file_id " +
            "WHERE a.opinion_id=#{opinionId} ORDER BY a.sort_no, a.id")
    List<OpinionAttachmentViewRecord> findByOpinionId(long opinionId);
}
