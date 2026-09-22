package com.bms.review.application;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerProcessStatus;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.audit.infrastructure.OperationAuditMapper;
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
    private final com.bms.identity.application.TaskNodeAuthorizationService taskNodeAuthorizationService =
            mock(com.bms.identity.application.TaskNodeAuthorizationService.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final ReviewerAssignmentService service = new ReviewerAssignmentService(reviewerMapper, taskMapper, taskNodeAuthorizationService,
            outboxEventPublisher, auditMapper);
    private final CurrentUser pcbLeader = new CurrentUser(1L, Set.of(Role.PCB_LEADER));

    @Test
    void shouldAssignMultipleMutualCheckReviewers() {
        when(reviewerMapper.nextId()).thenReturn(101L, 102L);
        when(taskMapper.findById(1001L)).thenReturn(task());
        when(reviewerMapper.findActiveByTaskAndRole(1001L, ReviewRole.PCB_MUTUAL_CHECK.name())).thenReturn(List.of());

        List<ReviewerAssignmentService.ReviewerView> reviewers = service.assign(1001L, ReviewRole.PCB_MUTUAL_CHECK,
                List.of(20L, 21L), pcbLeader);

        assertThat(reviewers).hasSize(2).allMatch(view -> view.status() == ReviewerProcessStatus.PENDING);
        verify(reviewerMapper, org.mockito.Mockito.times(2)).insert(any(TaskReviewerRecord.class));
        verify(auditMapper).insert(any());
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

    private ReviewTaskRecord task() {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setReviewType(ReviewType.PCB.name());
        task.setDesignerId(10L);
        task.setStatus(TaskStatus.PENDING_MUTUAL_ASSIGNMENT.name());
        return task;
    }
}
