package com.bms.review.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 评审意见聚合根，维护提出人、答复内容、确认结果和撤回记录，并保证设计者答复后仅由原提出人确认。
 */


public final class ReviewOpinion {
    private final Long taskId;
    private final OpinionSourceType sourceType;
    private final String comment;
    private final String raisedByEmployeeNo;
    private final List<OpinionReply> replies = new ArrayList<>();
    private final List<OpinionConfirmation> confirmations = new ArrayList<>();
    private OpinionStatus status;

    private ReviewOpinion(Long taskId, OpinionSourceType sourceType, String comment, String raisedByEmployeeNo) {
        this.taskId = Objects.requireNonNull(taskId);
        this.sourceType = Objects.requireNonNull(sourceType);
        this.comment = requireText(comment, "意见内容不能为空");
        this.raisedByEmployeeNo = requireText(raisedByEmployeeNo, "提出人工号不能为空");
        this.status = OpinionStatus.PENDING_REPLY;
    }

    public static ReviewOpinion raise(Long taskId, OpinionSourceType sourceType, String comment, String raisedByEmployeeNo) {
        return new ReviewOpinion(taskId, sourceType, comment, raisedByEmployeeNo);
    }

    public void reply(String replierEmployeeNo, ReplyType replyType, String reason) {
        requireState(OpinionStatus.PENDING_REPLY, "当前意见不允许答复");
        requireText(replierEmployeeNo, "答复人工号不能为空");
        Objects.requireNonNull(replyType);
        if (replyType != ReplyType.ACCEPT) {
            requireText(reason, "接受但不修改或不接受时必须填写原因");
        }
        replies.add(new OpinionReply(replierEmployeeNo, replyType, reason));
        status = OpinionStatus.PENDING_CONFIRMATION;
    }

    public void confirm(String confirmerEmployeeNo, boolean passed, String comment) {
        requireState(OpinionStatus.PENDING_CONFIRMATION, "当前意见不允许确认");
        if (!raisedByEmployeeNo.equals(confirmerEmployeeNo)) {
            throw new IllegalStateException("只有意见提出人可以确认");
        }
        confirmations.add(new OpinionConfirmation(confirmerEmployeeNo, passed, comment));
        status = passed ? OpinionStatus.CONFIRMED_PASS : OpinionStatus.PENDING_REPLY;
    }

    public void withdraw(String operatorEmployeeNo, String reason) {
        if (!raisedByEmployeeNo.equals(operatorEmployeeNo)) {
            throw new IllegalStateException("只有意见提出人可以撤回");
        }
        if (status == OpinionStatus.CONFIRMED_PASS || status == OpinionStatus.WITHDRAWN) {
            throw new IllegalStateException("已关闭意见不能撤回");
        }
        requireText(reason, "撤回原因不能为空");
        status = OpinionStatus.WITHDRAWN;
    }

    public OpinionStatus status() {
        return status;
    }

    public Long taskId() {
        return taskId;
    }

    public OpinionSourceType sourceType() {
        return sourceType;
    }

    public String raisedByEmployeeNo() {
        return raisedByEmployeeNo;
    }


    public List<OpinionReply> replies() {
        return List.copyOf(replies);
    }

    public List<OpinionConfirmation> confirmations() {
        return List.copyOf(confirmations);
    }

    private void requireState(OpinionStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public record OpinionReply(String replierEmployeeNo, ReplyType replyType, String reason) {
    }

    public record OpinionConfirmation(String confirmerEmployeeNo, boolean passed, String comment) {
    }
}
