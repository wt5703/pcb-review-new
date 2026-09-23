package com.bms.file.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 定义 review_file 表的当前文件读写操作，负责定位并替换同一业务键的当前文件，不承担文件权限判断。
 */
@Mapper
public interface ReviewFileMapper {
    @Select("SELECT COALESCE(MAX(id), 0) + 1 FROM review_file")
    long nextId();

    @Select("SELECT id, task_id AS taskId, file_category AS fileCategory, business_file_key AS businessFileKey, "
            + "file_id AS fileId, file_name AS fileName, file_format AS fileFormat, file_size AS fileSize, md5, resource_path AS resourcePath, "
            + "is_latest AS latest, uploaded_by AS uploadedBy, uploaded_at AS uploadedAt, uploaded_stage AS uploadedStage FROM review_file WHERE task_id=#{taskId} "
            + "AND file_category=#{fileCategory} AND business_file_key=#{businessFileKey} AND is_latest=TRUE")
    ReviewFileRecord findLatest(long taskId, String fileCategory, String businessFileKey);

    @Select("SELECT id, task_id AS taskId, file_category AS fileCategory, business_file_key AS businessFileKey, "
            + "file_id AS fileId, file_name AS fileName, file_format AS fileFormat, file_size AS fileSize, md5, resource_path AS resourcePath, "
            + "is_latest AS latest, uploaded_by AS uploadedBy, uploaded_at AS uploadedAt, uploaded_stage AS uploadedStage FROM review_file WHERE id=#{id}")
    ReviewFileRecord findById(long id);

    @Select("SELECT id, task_id AS taskId, file_category AS fileCategory, business_file_key AS businessFileKey, "
            + "file_id AS fileId, file_name AS fileName, file_format AS fileFormat, file_size AS fileSize, md5, resource_path AS resourcePath, "
            + "is_latest AS latest, uploaded_by AS uploadedBy, uploaded_at AS uploadedAt, uploaded_stage AS uploadedStage FROM review_file WHERE file_id=#{fileId}")
    ReviewFileRecord findByFileId(String fileId);

    @Select("SELECT id, task_id AS taskId, file_category AS fileCategory, business_file_key AS businessFileKey, file_name AS fileName, "
            + "file_id AS fileId, file_format AS fileFormat, file_size AS fileSize, md5, resource_path AS resourcePath, is_latest AS latest, uploaded_by AS uploadedBy, uploaded_at AS uploadedAt, uploaded_stage AS uploadedStage "
            + "FROM review_file WHERE task_id=#{taskId} AND is_latest=TRUE ORDER BY id")
    java.util.List<ReviewFileRecord> findLatestByTaskId(long taskId);

    @Select("SELECT id, task_id AS taskId, file_category AS fileCategory, business_file_key AS businessFileKey, file_name AS fileName, "
            + "file_id AS fileId, file_format AS fileFormat, file_size AS fileSize, md5, resource_path AS resourcePath, is_latest AS latest, uploaded_by AS uploadedBy, uploaded_at AS uploadedAt, uploaded_stage AS uploadedStage "
            + "FROM review_file WHERE task_id=#{taskId} AND file_category=#{fileCategory} AND is_latest=TRUE ORDER BY uploaded_at DESC, id DESC")
    java.util.List<ReviewFileRecord> findLatestByTaskIdAndCategory(long taskId, String fileCategory);

    @Update("UPDATE review_file SET is_latest=FALSE WHERE task_id=#{taskId} AND file_category=#{fileCategory} "
            + "AND business_file_key=#{businessFileKey} AND is_latest=TRUE")
    int markLatestAsHistorical(long taskId, String fileCategory, String businessFileKey);

    @Update("UPDATE review_file SET file_id=#{fileId}, file_name=#{fileName}, file_format=#{fileFormat}, file_size=#{fileSize}, md5=#{md5}, resource_path=#{resourcePath}, "
            + "uploaded_by=#{uploadedBy}, uploaded_at=CURRENT_TIMESTAMP, uploaded_stage=#{uploadedStage} WHERE id=#{id}")
    int updateCurrent(ReviewFileRecord record);

    @Update("UPDATE review_file SET task_id=#{taskId}, file_category=#{fileCategory}, business_file_key=#{businessFileKey}, "
            + "is_latest=TRUE, uploaded_stage=#{uploadedStage} WHERE id=#{id} AND task_id IS NULL")
    int bindToTask(ReviewFileRecord record);

    @Insert("INSERT INTO review_file (id, task_id, file_category, business_file_key, file_name, file_format, file_size, md5, "
            + "file_id, resource_path, is_latest, uploaded_by, uploaded_stage) VALUES (#{id}, #{taskId}, #{fileCategory}, #{businessFileKey}, #{fileName}, #{fileFormat}, "
            + "#{fileSize}, #{md5}, #{fileId}, #{resourcePath}, #{latest}, #{uploadedBy}, #{uploadedStage})")
    int insert(ReviewFileRecord record);
}
