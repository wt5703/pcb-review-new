package com.bms.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 描述任务内固定检查项是否已经由互检人员提交处理；其状态独立于任务整体流程状态和关联意见的闭环状态。
 */
public enum CheckItemStatus {
    PENDING,
    COMPLETED
}
