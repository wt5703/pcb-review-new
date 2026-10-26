package com.bms.task.domain;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 标识当前用户在“我的任务”中真正需要执行的业务动作，避免将仅有查看权限或创建历史的任务误展示为待办事项。
 */
public enum MyTaskAction {
    REVIEW,
    /** 设计者在当前阶段需要提交流程。 */
    SUBMIT_WORKFLOW,
    REPLY_OPINION,
    CONFIRM_OPINION,
    ASSIGN_REVIEWERS,
    FINISH_TASK
}
