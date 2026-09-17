package com.leapmotor.pcbreview.review.domain;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 表示单个评审人在任务节点上的处理进度，并封装从待处理到已完成的状态变化，供多人全员完成规则聚合计算。
 */


public record ReviewerProgress(Long reviewerId, ReviewerProcessStatus status, List<OpinionStatus> opinionStatuses) {

    public static ReviewerProgress submitted(Long reviewerId, List<OpinionStatus> opinionStatuses) {
        return new ReviewerProgress(reviewerId, ReviewerProcessStatus.SUBMITTED, List.copyOf(opinionStatuses));
    }

    public static ReviewerProgress inProgress(Long reviewerId, List<OpinionStatus> opinionStatuses) {
        return new ReviewerProgress(reviewerId, ReviewerProcessStatus.IN_PROGRESS, List.copyOf(opinionStatuses));
    }

    public boolean isSubmitted() {
        return status == ReviewerProcessStatus.SUBMITTED || status == ReviewerProcessStatus.COMPLETED;
    }
}
