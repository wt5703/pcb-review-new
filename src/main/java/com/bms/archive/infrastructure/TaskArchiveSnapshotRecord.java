package com.bms.archive.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射已结束任务的只读归档快照，分别保存最终任务、文件、参与人、流程节点和通知摘要；保留 opinionSnapshot 物理列仅为兼容已升级数据库。
 */
public record TaskArchiveSnapshotRecord(Long taskId, String finalStatus, String taskSnapshot, String fileSnapshot,
                                        String reviewerSnapshot, String opinionSnapshot, String flowSnapshot,
                                        String notificationSnapshot) {
}
