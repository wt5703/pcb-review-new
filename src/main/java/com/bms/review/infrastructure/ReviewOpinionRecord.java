package com.bms.review.infrastructure;

import com.bms.review.domain.OpinionSeverity;
import com.bms.review.domain.OpinionSourceType;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 映射 review_opinion 表的一条主意见记录，保存来源、提出人和当前闭环状态；答复及确认历史由独立记录保存。
 */
public class ReviewOpinionRecord {
    private Long id;
    private Long taskId;
    private OpinionSourceType sourceType;
    private Long sourceItemId;
    private OpinionSeverity severity;
    private boolean noOpinion;
    private String comment;
    private String richText;
    private String raisedByEmployeeNo;
    private String raisedByName;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public OpinionSourceType getSourceType() { return sourceType; }
    public void setSourceType(OpinionSourceType sourceType) { this.sourceType = sourceType; }
    public Long getSourceItemId() { return sourceItemId; }
    public void setSourceItemId(Long sourceItemId) { this.sourceItemId = sourceItemId; }
    public OpinionSeverity getSeverity() { return severity; }
    public void setSeverity(OpinionSeverity severity) { this.severity = severity; }
    public boolean isNoOpinion() { return noOpinion; }
    public void setNoOpinion(boolean noOpinion) { this.noOpinion = noOpinion; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getRichText() { return richText; }
    public void setRichText(String richText) { this.richText = richText; }
    public String getRaisedByEmployeeNo() { return raisedByEmployeeNo; }
    public void setRaisedByEmployeeNo(String raisedByEmployeeNo) { this.raisedByEmployeeNo = raisedByEmployeeNo; }
    public String getRaisedByName() { return raisedByName; }
    public void setRaisedByName(String raisedByName) { this.raisedByName = raisedByName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
