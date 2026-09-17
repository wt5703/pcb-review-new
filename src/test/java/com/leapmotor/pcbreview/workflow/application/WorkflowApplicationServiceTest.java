package com.leapmotor.pcbreview.workflow.application;

import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditMapper;
import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileMapper;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileRecord;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Role;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventMapper;
import com.leapmotor.pcbreview.review.application.TaskCheckItemApplicationService;
import com.leapmotor.pcbreview.review.domain.ReviewerProcessStatus;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerRecord;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import com.leapmotor.pcbreview.workflow.domain.WorkflowAction;
import com.leapmotor.pcbreview.workflow.infrastructure.TaskFlowMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证流程服务对合法阶段推进、多人完成后的任务结束及已结束任务不可重新打开的核心约束，并确认流转会写入审计和 Outbox。
 */
class WorkflowApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskReviewerMapper reviewerMapper = mock(TaskReviewerMapper.class);
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final TaskCheckItemApplicationService checkItemApplicationService = mock(TaskCheckItemApplicationService.class);
    private final TaskFlowMapper flowMapper = mock(TaskFlowMapper.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
    private final WorkflowApplicationService service = new WorkflowApplicationService(taskMapper, reviewerMapper, opinionMapper,
            fileMapper, checkItemApplicationService, flowMapper, auditMapper, outboxEventMapper);

    @Test
    void shouldStartPcbExpertReviewForAuthorizedDesigner() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PCB_PENDING_REVIEW));
        when(taskMapper.update(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(1L);

        WorkflowApplicationService.WorkflowView view = service.transition(1001L, WorkflowAction.START_PCB_EXPERT_REVIEW, 0L, null,
                new CurrentUser(10L, Set.of(Role.DESIGNER)));

        assertThat(view.toStatus()).isEqualTo(TaskStatus.PCB_EXPERT_REVIEWING);
        verify(auditMapper).insert(any());
        verify(outboxEventMapper).insert(any());
    }

    @Test
    void shouldFinishOnlyAfterEveryReviewerSubmittedAndLatestFileExists() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PENDING_FINISH_CONFIRMATION));
        when(reviewerMapper.findActiveByTaskId(1001L)).thenReturn(List.of(reviewer(20L)));
        when(opinionMapper.findStatusesByTaskAndRaisedBy(1001L, 20L)).thenReturn(List.of());
        when(fileMapper.findLatestByTaskId(1001L)).thenReturn(List.of(new ReviewFileRecord()));
        when(taskMapper.update(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(2L);

        WorkflowApplicationService.WorkflowView view = service.transition(1001L, WorkflowAction.FINISH, 0L, "评审结束",
                new CurrentUser(1L, Set.of(Role.PCB_LEADER)));

        assertThat(view.toStatus()).isEqualTo(TaskStatus.FINISHED);
        verify(checkItemApplicationService).materializeActiveTaskItems(1001L);
    }

    @Test
    void shouldNeverReopenFinishedTask() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.FINISHED));

        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.FINISH, 0L, null,
                new CurrentUser(1L, Set.of(Role.PCB_LEADER))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已结束任务不允许重新打开或流转");
    }

    @Test
    void shouldPrepareSchematicExpertAssignmentOnlyAfterMutualReviewersCompleted() {
        when(taskMapper.findById(1001L)).thenReturn(task(ReviewType.SCHEMATIC, TaskStatus.MUTUAL_REVIEWING));
        when(reviewerMapper.findActiveByTaskId(1001L)).thenReturn(List.of(reviewer(20L)));
        when(opinionMapper.findStatusesByTaskAndRaisedBy(1001L, 20L)).thenReturn(List.of());
        when(taskMapper.update(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(3L);

        WorkflowApplicationService.WorkflowView view = service.transition(1001L, WorkflowAction.PREPARE_SCHEMATIC_EXPERT_ASSIGNMENT,
                0L, null, new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(view.toStatus()).isEqualTo(TaskStatus.SCHEMATIC_PENDING_REVIEW);
    }

    private ReviewTaskRecord task(TaskStatus status) {
        return task(ReviewType.PCB, status);
    }

    private ReviewTaskRecord task(ReviewType reviewType, TaskStatus status) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setReviewType(reviewType.name());
        task.setTaskName("BMS PCB评审");
        task.setProjectName("BMS");
        task.setDesignerId(10L);
        task.setDesignName("BMS-P1");
        task.setStatus(status.name());
        task.setInitialFileIds("");
        task.setVersion(0L);
        return task;
    }

    private TaskReviewerRecord reviewer(long reviewerId) {
        TaskReviewerRecord reviewer = new TaskReviewerRecord();
        reviewer.setReviewerId(reviewerId);
        reviewer.setProcessStatus(ReviewerProcessStatus.SUBMITTED.name());
        return reviewer;
    }
}
