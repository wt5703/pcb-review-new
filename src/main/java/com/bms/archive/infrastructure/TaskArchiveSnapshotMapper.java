package com.bms.archive.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 定义任务归档快照的追加读取接口，任务标识唯一，确保同一任务结束的并发请求至多生成一份归档快照。
 */
@Mapper
public interface TaskArchiveSnapshotMapper {
    @Insert("INSERT INTO task_archive_snapshot (task_id, file_snapshot, notification_snapshot) "
            + "VALUES (#{taskId}, #{fileSnapshot}, #{notificationSnapshot})")
    int insert(TaskArchiveSnapshotRecord record);

    @Select("SELECT CASE WHEN EXISTS (SELECT 1 FROM task_archive_snapshot WHERE task_id=#{taskId}) THEN TRUE ELSE FALSE END")
    boolean existsByTaskId(long taskId);

    @Select("SELECT task_id AS taskId, file_snapshot AS fileSnapshot, notification_snapshot AS notificationSnapshot "
            + "FROM task_archive_snapshot WHERE task_id=#{taskId}")
    TaskArchiveSnapshotRecord findByTaskId(long taskId);
}
