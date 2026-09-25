package com.bms.workflow.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射任务提交或流程推进记录，保存动作枚举、动作中文名称、操作人和说明，为任务归档提供不可变流程历史。
 */
public class TaskFlowRecord {
    private Long id;
    private Long taskId;
    private String action;
    private String actionName;
    private Long operateId;
    private String comment;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getActionName() { return actionName; }
    public void setActionName(String actionName) { this.actionName = actionName; }
    public Long getOperateId() { return operateId; }
    public void setOperateId(Long operateId) { this.operateId = operateId; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
