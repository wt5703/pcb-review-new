package com.bms.review.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 映射设计者对一条意见的单次答复历史，记录答复方式、文字说明及同一意见内的顺序号。
 */
public class OpinionReplyRecord {
    private Long id;
    private Long opinionId;
    private String replyType;
    private String reason;
    private Long repliedBy;
    private Integer replyNo;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOpinionId() { return opinionId; }
    public void setOpinionId(Long opinionId) { this.opinionId = opinionId; }
    public String getReplyType() { return replyType; }
    public void setReplyType(String replyType) { this.replyType = replyType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getRepliedBy() { return repliedBy; }
    public void setRepliedBy(Long repliedBy) { this.repliedBy = repliedBy; }
    public Integer getReplyNo() { return replyNo; }
    public void setReplyNo(Integer replyNo) { this.replyNo = replyNo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
