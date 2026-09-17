package com.leapmotor.pcbreview.file.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 定义 review_file 表的版本链读写操作，负责定位最新版本、使旧版本失效及保存新版本，不承担文件权限判断。
 */
@Mapper
public interface ReviewFileMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM review_file")
    long nextId();

    @Select("SELECT id, task_id AS taskId, file_category AS fileCategory, business_file_key AS businessFileKey, "
            + "file_name AS fileName, file_size AS fileSize, md5, version_no AS versionNo, company_file_id AS companyFileId, "
            + "is_latest AS latest, uploaded_by AS uploadedBy FROM review_file WHERE task_id=#{taskId} "
            + "AND file_category=#{fileCategory} AND business_file_key=#{businessFileKey} AND is_latest=TRUE")
    ReviewFileRecord findLatest(long taskId, String fileCategory, String businessFileKey);

    @Select("SELECT id, task_id AS taskId, file_category AS fileCategory, business_file_key AS businessFileKey, "
            + "file_name AS fileName, file_size AS fileSize, md5, version_no AS versionNo, company_file_id AS companyFileId, "
            + "is_latest AS latest, uploaded_by AS uploadedBy FROM review_file WHERE id=#{id}")
    ReviewFileRecord findById(long id);

    @Select("SELECT id, task_id AS taskId, file_category AS fileCategory, business_file_key AS businessFileKey, file_name AS fileName, "
            + "file_size AS fileSize, md5, version_no AS versionNo, company_file_id AS companyFileId, is_latest AS latest, uploaded_by AS uploadedBy "
            + "FROM review_file WHERE task_id=#{taskId} AND is_latest=TRUE ORDER BY id")
    java.util.List<ReviewFileRecord> findLatestByTaskId(long taskId);

    @Update("UPDATE review_file SET is_latest=FALSE WHERE task_id=#{taskId} AND file_category=#{fileCategory} "
            + "AND business_file_key=#{businessFileKey} AND is_latest=TRUE")
    int markLatestAsHistorical(long taskId, String fileCategory, String businessFileKey);

    @Insert("INSERT INTO review_file (id, task_id, file_category, business_file_key, file_name, file_size, md5, version_no, "
            + "company_file_id, is_latest, uploaded_by) VALUES (#{id}, #{taskId}, #{fileCategory}, #{businessFileKey}, #{fileName}, "
            + "#{fileSize}, #{md5}, #{versionNo}, #{companyFileId}, #{latest}, #{uploadedBy})")
    int insert(ReviewFileRecord record);
}
