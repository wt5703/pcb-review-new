package com.bms.archive.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射已结束任务的只读归档快照，冻结阶段文件、流程流转记录和邮件投递记录。
 */
public record TaskArchiveSnapshotRecord(Long taskId, String fileSnapshot, String flowSnapshot, String notificationSnapshot) {
}
