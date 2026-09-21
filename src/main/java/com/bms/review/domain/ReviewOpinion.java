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
    private final String sourceType;
    private final String content;
    private final Long raisedBy;
    private final List<OpinionReply> replies = new ArrayList<>();
    private final List<OpinionConfirmation> confirmations = new ArrayList<>();
    private OpinionStatus status;

    private ReviewOpinion(Long taskId, String sourceType, String content, Long raisedBy) {
        this.taskId = Objects.requireNonNull(taskId);
        this.sourceType = Objects.requireNonNull(sourceType);
        this.content = requireText(content, "意见内容不能为空");
        this.raisedBy = Objects.requireNonNull(raisedBy);
        this.status = OpinionStatus.PENDING_REPLY;
    }

    public static ReviewOpinion raise(Long taskId, String sourceType, String content, Long raisedBy) {
        return new ReviewOpinion(taskId, sourceType, content, raisedBy);
    }

    public void reply(Long replierId, ReplyType replyType, String reason) {
        requireState(OpinionStatus.PENDING_REPLY, "当前意见不允许答复");
        Objects.requireNonNull(replierId);
        Objects.requireNonNull(replyType);
        if (replyType != ReplyType.ACCEPT) {
            requireText(reason, "接受但不修改或不接受时必须填写原因");
        }
        replies.add(new OpinionReply(replierId, replyType, reason));
        status = OpinionStatus.PENDING_CONFIRMATION;
    }

    public void confirm(Long confirmerId, boolean passed, String comment) {
        requireState(OpinionStatus.PENDING_CONFIRMATION, "当前意见不允许确认");
        if (!raisedBy.equals(confirmerId)) {
            throw new IllegalStateException("只有意见提出人可以确认");
        }
        confirmations.add(new OpinionConfirmation(confirmerId, passed, comment));
        status = passed ? OpinionStatus.CONFIRMED_PASS : OpinionStatus.PENDING_REPLY;
    }

    public void withdraw(Long operatorId, String reason) {
        if (!raisedBy.equals(operatorId)) {
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

    public Long raisedBy() {
        return raisedBy;
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

    public record OpinionReply(Long replierId, ReplyType replyType, String reason) {
    }

    public record OpinionConfirmation(Long confirmerId, boolean passed, String comment) {
    }
}
