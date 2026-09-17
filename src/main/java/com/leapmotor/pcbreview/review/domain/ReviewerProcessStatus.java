package com.leapmotor.pcbreview.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 标记某位评审参与者在指定任务节点的处理进度，用于在多人评审时判定是否仍有未完成成员。
 */


public enum ReviewerProcessStatus {
    PENDING,
    IN_PROGRESS,
    SUBMITTED,
    COMPLETED,
    CANCELLED
}
