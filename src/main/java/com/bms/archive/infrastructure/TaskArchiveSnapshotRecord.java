package com.bms.archive.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射已结束任务的只读归档快照，只冻结阶段文件和通知投递记录；流程节点始终从 task_flow_record 实时读取。
 */
public record TaskArchiveSnapshotRecord(Long taskId, String fileSnapshot, String notificationSnapshot) {
}
