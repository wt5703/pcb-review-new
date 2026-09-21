package com.bms.workflow.application;

import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.common.BusinessException;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.file.application.FileApplicationService;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.review.application.TaskCheckItemApplicationService;
import com.bms.review.application.ReviewerAssignmentService;
import com.bms.review.domain.ReviewerProcessStatus;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.domain.WorkflowAction;
import com.bms.workflow.infrastructure.TaskFlowMapper;
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
    private final TaskArchiveApplicationService taskArchiveApplicationService = mock(TaskArchiveApplicationService.class);
    private final ReviewerAssignmentService reviewerAssignmentService = mock(ReviewerAssignmentService.class);
    private final FileApplicationService fileApplicationService = mock(FileApplicationService.class);
    private final WorkflowApplicationService service = new WorkflowApplicationService(taskMapper, reviewerMapper, opinionMapper,
            fileMapper, checkItemApplicationService, flowMapper, auditMapper, outboxEventMapper, taskArchiveApplicationService,
            reviewerAssignmentService, fileApplicationService);

    @Test
    void shouldStartPcbExpertReviewForAuthorizedDesigner() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PCB_PENDING_REVIEW));
        when(taskMapper.update(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(1L);

        WorkflowApplicationService.WorkflowView view = service.transition(1001L, WorkflowAction.START_PCB_EXPERT_REVIEW, null,
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

        WorkflowApplicationService.WorkflowView view = service.transition(1001L, WorkflowAction.FINISH, "评审结束",
                new CurrentUser(1L, Set.of(Role.PCB_LEADER)));

        assertThat(view.toStatus()).isEqualTo(TaskStatus.FINISHED);
        verify(checkItemApplicationService).materializeActiveTaskItems(1001L);
        verify(taskArchiveApplicationService).archive(any(ReviewTaskRecord.class));
    }

    @Test
    void shouldNeverReopenFinishedTask() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.FINISHED));

        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.FINISH, null,
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
                null, new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(view.toStatus()).isEqualTo(TaskStatus.SCHEMATIC_PENDING_REVIEW);
    }

    @Test
    void shouldReportMissingTaskWhenStatusUpdateDoesNotPersist() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PENDING_FINISH_CONFIRMATION));
        when(reviewerMapper.findActiveByTaskId(1001L)).thenReturn(List.of(reviewer(20L)));
        when(opinionMapper.findStatusesByTaskAndRaisedBy(1001L, 20L)).thenReturn(List.of());
        when(fileMapper.findLatestByTaskId(1001L)).thenReturn(List.of(new ReviewFileRecord()));
        when(taskMapper.update(any())).thenReturn(0);

        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.FINISH, null,
                new CurrentUser(1L, Set.of(Role.PCB_LEADER))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务不存在");
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
