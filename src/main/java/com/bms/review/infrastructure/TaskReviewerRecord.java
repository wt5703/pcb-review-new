package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 对应 task_reviewer 表的一条任务参与者记录，保存被分配职责、处理进度、无意见标记和改派后的历史状态，不包含意见内容。
 */
public class TaskReviewerRecord {
    private Long id;
    private Long taskId;
    private String reviewRole;
    private Long reviewerId;
    private String processStatus;
    private Long assignedBy;
    private Boolean noOpinion;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getReviewRole() { return reviewRole; }
    public void setReviewRole(String reviewRole) { this.reviewRole = reviewRole; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getProcessStatus() { return processStatus; }
    public void setProcessStatus(String processStatus) { this.processStatus = processStatus; }
    public Long getAssignedBy() { return assignedBy; }
    public void setAssignedBy(Long assignedBy) { this.assignedBy = assignedBy; }
    public Boolean getNoOpinion() { return noOpinion; }
    public void setNoOpinion(Boolean noOpinion) { this.noOpinion = noOpinion; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
