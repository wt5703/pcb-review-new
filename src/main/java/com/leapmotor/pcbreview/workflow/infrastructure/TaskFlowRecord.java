package com.leapmotor.pcbreview.workflow.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射单次任务状态流转记录，保存流转前后状态、动作、操作者和备注，为任务结束后审计与问题排查提供不可变历史。
 */
public class TaskFlowRecord {
    private Long id;
    private Long taskId;
    private String fromStatus;
    private String toStatus;
    private String action;
    private Long operatorId;
    private String comment;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
