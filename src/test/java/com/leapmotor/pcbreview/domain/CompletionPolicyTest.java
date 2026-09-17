package com.leapmotor.pcbreview.domain;

import com.leapmotor.pcbreview.review.domain.OpinionStatus;
import com.leapmotor.pcbreview.review.domain.ReviewerProgress;
import com.leapmotor.pcbreview.workflow.domain.CompletionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 评审完成条件领域测试类。
 */


class CompletionPolicyTest {

    private final CompletionPolicy policy = new CompletionPolicy();

    @Test
    void allAssignedReviewersMustSubmitAndCloseTheirOpinions() {
        List<ReviewerProgress> progress = List.of(
                ReviewerProgress.submitted(1L, List.of(OpinionStatus.CONFIRMED_PASS)),
                ReviewerProgress.inProgress(2L, List.of())
        );

        assertThat(policy.isComplete(progress)).isFalse();
    }

    @Test
    void submittedNoOpinionAndConfirmedOpinionsCompleteRole() {
        List<ReviewerProgress> progress = List.of(
                ReviewerProgress.submitted(1L, List.of()),
                ReviewerProgress.submitted(2L, List.of(OpinionStatus.CONFIRMED_PASS))
        );

        assertThat(policy.isComplete(progress)).isTrue();
    }
}
