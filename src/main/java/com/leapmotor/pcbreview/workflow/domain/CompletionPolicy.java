package com.leapmotor.pcbreview.workflow.domain;

import com.leapmotor.pcbreview.review.domain.OpinionStatus;
import com.leapmotor.pcbreview.review.domain.ReviewerProgress;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 汇总同一任务节点所有评审人的处理进度，严格执行多人场景必须全部完成才可推进流程的完成条件。
 */


public final class CompletionPolicy {

    public boolean isComplete(List<ReviewerProgress> reviewerProgresses) {
        return !reviewerProgresses.isEmpty() && reviewerProgresses.stream()
                .allMatch(progress -> progress.isSubmitted() && progress.opinionStatuses().stream()
                        .allMatch(status -> status == OpinionStatus.CONFIRMED_PASS || status == OpinionStatus.WITHDRAWN));
    }
}
