package com.leapmotor.pcbreview.review.application;

import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Role;
import com.leapmotor.pcbreview.review.domain.ReviewRole;
import com.leapmotor.pcbreview.review.domain.ReviewerProcessStatus;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerRecord;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 验证评审人员分配、改派和无意见提交的应用编排，确保改派保留历史且多人分别提交后可供完成规则统一判断。
 */
class ReviewerAssignmentServiceTest {
    private final TaskReviewerMapper reviewerMapper = mock(TaskReviewerMapper.class);
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final com.leapmotor.pcbreview.identity.application.TaskNodeAuthorizationService taskNodeAuthorizationService =
            mock(com.leapmotor.pcbreview.identity.application.TaskNodeAuthorizationService.class);
    private final TaskAssignmentAccessMapper assignmentAccessMapper = mock(TaskAssignmentAccessMapper.class);
    private final ReviewerAssignmentService service = new ReviewerAssignmentService(reviewerMapper, taskMapper, taskNodeAuthorizationService,
            assignmentAccessMapper);
    private final CurrentUser pcbLeader = new CurrentUser(1L, Set.of(Role.PCB_LEADER));

    @Test
    void shouldAssignMultipleMutualCheckReviewers() {
        when(reviewerMapper.nextId()).thenReturn(101L, 102L);
        when(taskMapper.findById(1001L)).thenReturn(new com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord());
        when(reviewerMapper.findActiveByTaskAndRole(1001L, ReviewRole.PCB_MUTUAL_CHECK.name())).thenReturn(List.of());

        List<ReviewerAssignmentService.ReviewerView> reviewers = service.assign(1001L, ReviewRole.PCB_MUTUAL_CHECK,
                List.of(20L, 21L), pcbLeader);

        assertThat(reviewers).hasSize(2).allMatch(view -> view.status() == ReviewerProcessStatus.PENDING);
        verify(reviewerMapper, org.mockito.Mockito.times(2)).insert(any(TaskReviewerRecord.class));
    }

    @Test
    void shouldRequireEveryAssignedReviewerToSubmitBeforeRoleIsComplete() {
        when(reviewerMapper.findActiveByTaskAndRole(1001L, ReviewRole.PCB_MUTUAL_CHECK.name())).thenReturn(List.of(
                reviewer(20L, ReviewerProcessStatus.SUBMITTED), reviewer(21L, ReviewerProcessStatus.IN_PROGRESS)));

        assertThat(service.areAllReviewersSubmitted(1001L, ReviewRole.PCB_MUTUAL_CHECK)).isFalse();

        when(reviewerMapper.findActiveByTaskAndRole(1001L, ReviewRole.PCB_MUTUAL_CHECK.name())).thenReturn(List.of(
                reviewer(20L, ReviewerProcessStatus.SUBMITTED), reviewer(21L, ReviewerProcessStatus.COMPLETED)));
        assertThat(service.areAllReviewersSubmitted(1001L, ReviewRole.PCB_MUTUAL_CHECK)).isTrue();
    }

    private TaskReviewerRecord reviewer(long reviewerId, ReviewerProcessStatus status) {
        TaskReviewerRecord record = new TaskReviewerRecord();
        record.setReviewerId(reviewerId);
        record.setProcessStatus(status.name());
        return record;
    }
}
