package com.leapmotor.pcbreview.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 映射意见提出人对指定答复的一次确认历史，记录通过/不通过结果、备注和确认人，供意见闭环审计与回溯。
 */
public class OpinionConfirmationRecord {
    private Long id;
    private Long opinionId;
    private Long replyId;
    private Boolean passed;
    private String comment;
    private Long confirmedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOpinionId() { return opinionId; }
    public void setOpinionId(Long opinionId) { this.opinionId = opinionId; }
    public Long getReplyId() { return replyId; }
    public void setReplyId(Long replyId) { this.replyId = replyId; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long confirmedBy) { this.confirmedBy = confirmedBy; }
}
