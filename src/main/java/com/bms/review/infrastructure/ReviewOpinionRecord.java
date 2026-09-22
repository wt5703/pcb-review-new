package com.bms.review.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 映射 review_opinion 表的一条主意见记录，保存来源、提出人和当前闭环状态；答复及确认历史由独立记录保存。
 */
public class ReviewOpinionRecord {
    private Long id;
    private Long taskId;
    private String sourceType;
    private Long sourceItemId;
    private String severity;
    private String content;
    private String richText;
    private Long raisedBy;
    private String raisedByName;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceItemId() { return sourceItemId; }
    public void setSourceItemId(Long sourceItemId) { this.sourceItemId = sourceItemId; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getRichText() { return richText; }
    public void setRichText(String richText) { this.richText = richText; }
    public Long getRaisedBy() { return raisedBy; }
    public void setRaisedBy(Long raisedBy) { this.raisedBy = raisedBy; }
    public String getRaisedByName() { return raisedByName; }
    public void setRaisedByName(String raisedByName) { this.raisedByName = raisedByName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
