package com.leapmotor.pcbreview.archive.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射已结束任务的只读归档快照，分别保存最终任务、文件、参与人、意见、流程和通知摘要，避免后续业务表变化影响历史回溯。
 */
public record TaskArchiveSnapshotRecord(Long taskId, String finalStatus, String taskSnapshot, String fileSnapshot,
                                        String reviewerSnapshot, String opinionSnapshot, String flowSnapshot,
                                        String notificationSnapshot) {
}
