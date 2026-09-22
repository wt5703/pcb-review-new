package com.bms.file.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 读写创建任务前上传的临时文件元数据；文件 UUID 是任务保存请求唯一需要传回的文件字段。
 */
@Mapper
public interface PendingFileUploadMapper {
    @Insert("INSERT INTO pending_file_upload (file_id, file_category, file_name, file_format, file_size, md5, resource_path, uploaded_by) "
            + "VALUES (#{fileId}, #{fileCategory}, #{fileName}, #{fileFormat}, #{fileSize}, #{md5}, #{resourcePath}, #{uploadedBy})")
    int insert(PendingFileUploadRecord record);

    @Select("SELECT file_id AS fileId, file_category AS fileCategory, file_name AS fileName, file_format AS fileFormat, file_size AS fileSize, "
            + "md5, resource_path AS resourcePath, uploaded_by AS uploadedBy, uploaded_at AS uploadedAt FROM pending_file_upload WHERE file_id=#{fileId}")
    PendingFileUploadRecord findByFileId(String fileId);
}
