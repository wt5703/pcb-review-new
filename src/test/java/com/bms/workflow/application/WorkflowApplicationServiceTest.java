package com.bms.workflow.application;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.common.BusinessException;
import com.bms.file.application.FileApplicationService;
import com.bms.file.domain.FileCategory;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.application.ReviewMailNotificationApplicationService;
import com.bms.review.application.TaskCheckItemApplicationService;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.domain.WorkflowAction;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证流程服务对合法阶段推进、多人完成后的任务结束及已结束任务不可重新打开的核心约束，并确认流转会写入审计和 Outbox。
 */
class WorkflowApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final FileApplicationService fileApplicationService = mock(FileApplicationService.class);
    private final TaskCheckItemApplicationService checkItemApplicationService = mock(TaskCheckItemApplicationService.class);
    private final TaskFlowMapper flowMapper = mock(TaskFlowMapper.class);
    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
    private final TaskArchiveApplicationService taskArchiveApplicationService = mock(TaskArchiveApplicationService.class);
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService = mock(ReviewerWhitelistApplicationService.class);
    private final ReviewMailNotificationApplicationService reviewMailNotificationApplicationService = mock(ReviewMailNotificationApplicationService.class);
    private final WorkflowApplicationService service = new WorkflowApplicationService(taskMapper, opinionMapper,
            fileMapper, checkItemApplicationService, flowMapper, outboxEventMapper, taskArchiveApplicationService,
            reviewerWhitelistApplicationService, reviewMailNotificationApplicationService, fileApplicationService);

    @BeforeEach
    void allowActiveReviewerPersistence() {
        when(taskMapper.updateAssignedReviewerEmployeeNos(any())).thenReturn(1);
    }

    @Test
    void shouldStartPcbProcessReviewForAuthorizedDesignerAfterExpertOpinionsPassed() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PCB_EXPERT_REVIEWING));
        ReviewOpinionRecord submitted = new ReviewOpinionRecord();
        submitted.setSourceType(OpinionSourceType.PCB_REVIEW); submitted.setRaisedByEmployeeNo("BMS010"); submitted.setStatus("CONFIRMED_PASS");
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(submitted));
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, "PCB_PROCESS_REVIEW")).thenReturn(List.of(new ReviewFileRecord()));
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, "PCB_STRUCTURE_REVIEW")).thenReturn(List.of(new ReviewFileRecord()));
        when(taskMapper.update(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(1L);

        WorkflowApplicationService.WorkflowView view = service.transition(1001L,
                new WorkflowApplicationService.TransitionBatchCommand(List.of(
                        WorkflowAction.START_PCB_STRUCTURE_REVIEW, WorkflowAction.START_PCB_PROCESS_REVIEW), null, List.of()),
                "BMS010", "BMS010");

        assertThat(view.toStatus()).isEqualTo(TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING);
        verify(outboxEventMapper, times(2)).insert(any());
        verify(reviewMailNotificationApplicationService).enqueuePcbStageReview(any(), eq(List.of(
                FileCategory.PCB_STRUCTURE_REVIEW, FileCategory.PCB_PROCESS_REVIEW)));
    }

    @Test
    void shouldStartPcbStructureReviewIndependently() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PCB_EXPERT_REVIEWING));
        ReviewOpinionRecord submitted = new ReviewOpinionRecord();
        submitted.setSourceType(OpinionSourceType.PCB_REVIEW); submitted.setRaisedByEmployeeNo("BMS010"); submitted.setStatus("CONFIRMED_PASS");
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(submitted));
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, "PCB_STRUCTURE_REVIEW")).thenReturn(List.of(new ReviewFileRecord()));
        when(taskMapper.update(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(2L);

        WorkflowApplicationService.WorkflowView view = service.transition(1001L,
                new WorkflowApplicationService.TransitionBatchCommand(List.of(WorkflowAction.START_PCB_STRUCTURE_REVIEW), null, List.of()),
                "BMS010", "BMS010");

        assertThat(view.toStatus()).isEqualTo(TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING);
        verify(reviewMailNotificationApplicationService).enqueuePcbStageReview(any(), eq(List.of(FileCategory.PCB_STRUCTURE_REVIEW)));
    }

    @Test
    void shouldRejectStartingPcbStageReviewWhenAnAssignedExpertHasNotSubmitted() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PCB_EXPERT_REVIEWING));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.transition(1001L,
                new WorkflowApplicationService.TransitionBatchCommand(List.of(
                        WorkflowAction.START_PCB_STRUCTURE_REVIEW, WorkflowAction.START_PCB_PROCESS_REVIEW), null, List.of()),
                "BMS010", "BMS010"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仍有已分配专家未提交评审意见或确认无意见，不能开启工艺或结构评审");
    }

    @Test
    void shouldFinishWhenAllOpinionsHaveBeenConfirmed() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PENDING_FINISH));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of());
        when(taskMapper.update(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(2L);

        WorkflowApplicationService.WorkflowView view = service.transition(1001L, WorkflowAction.FINISH, "评审结束",
                "BMS001", "BMS001");

        assertThat(view.toStatus()).isEqualTo(TaskStatus.FINISHED);
        verify(checkItemApplicationService).materializeActiveTaskItems(1001L);
        verify(taskArchiveApplicationService).archive(any(ReviewTaskRecord.class));
    }

    @Test
    void shouldNeverReopenFinishedTask() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.FINISHED));

        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.FINISH, null,
                "BMS001", "BMS001"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已结束任务不允许重新打开或流转");
    }

    @Test
    void shouldPreparePcbFinishAfterMutualReviewAndFinalFile() {
        assertPrepareFinish(ReviewType.PCB, TaskStatus.MUTUAL_CHECK_REVIEWING, FileCategory.PCB_REVIEW);
    }

    @Test
    void shouldPrepareSchematicFinishAfterSchematicReviewAndFinalFile() {
        assertPrepareFinish(ReviewType.SCHEMATIC, TaskStatus.SCHEMATIC_REVIEWING, FileCategory.SCHEMATIC_REVIEW);
    }

    private void assertPrepareFinish(ReviewType type, TaskStatus status, FileCategory category) {
        ReviewTaskRecord task = task(type, status);
        when(taskMapper.findById(1001L)).thenReturn(task);
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of());
        ReviewFileRecord file = new ReviewFileRecord();
        file.setUploadedStage(status.name());
        file.setUploadedByEmployeeNo("BMS010");
        file.setUploadedAt(LocalDateTime.now());
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, category.name())).thenReturn(List.of(file));
        when(taskMapper.update(any())).thenReturn(1);

        var result = service.transition(1001L, WorkflowAction.PENDING_FINISH, null,
                "BMS010", "BMS010");
        assertThat(result.fromStatus()).isEqualTo(status);
        assertThat(result.toStatus()).isEqualTo(TaskStatus.PENDING_FINISH);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING_FINISH.name());
        ArgumentCaptor<TaskFlowRecord> record = ArgumentCaptor.forClass(TaskFlowRecord.class);
        verify(flowMapper).insert(record.capture());
        assertThat(record.getValue().getAction()).isEqualTo("PENDING_FINISH");
        assertThat(record.getValue().getActionName()).isEqualTo("进入待结束");
    }

    @Test
    void shouldRejectFinalFileUploadedBeforeOpinionClosure() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.MUTUAL_CHECK_REVIEWING));
        ReviewOpinionRecord opinion = new ReviewOpinionRecord();
        opinion.setSourceType(OpinionSourceType.MUTUAL_EXTRA);
        opinion.setStatus("CONFIRMED_PASS");
        opinion.setUpdatedAt(LocalDateTime.of(2026, 10, 19, 10, 1));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(opinion));
        ReviewFileRecord file = new ReviewFileRecord();
        file.setUploadedStage(TaskStatus.MUTUAL_CHECK_REVIEWING.name());
        file.setUploadedByEmployeeNo("BMS010");
        file.setUploadedAt(LocalDateTime.of(2026, 10, 19, 10, 0));
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, FileCategory.PCB_REVIEW.name())).thenReturn(List.of(file));
        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.PENDING_FINISH, null,
                "BMS010", "BMS010"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请在评审意见全部处理完成后重新上传终版文件");
    }

    @Test
    void shouldRejectPrepareFinishWhenMutualOpinionIsNotPassed() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.MUTUAL_CHECK_REVIEWING));
        ReviewOpinionRecord opinion = new ReviewOpinionRecord();
        opinion.setSourceType(OpinionSourceType.MUTUAL_CHECK_ITEM);
        opinion.setStatus("PENDING_CONFIRMATION");
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(opinion));
        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.PENDING_FINISH, null,
                "BMS010", "BMS010"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("互检单意见尚未全部确认通过，不能进入待结束");
    }

    @Test
    void shouldRejectOriginalFileFromEarlierStage() {
        when(taskMapper.findById(1001L)).thenReturn(task(ReviewType.SCHEMATIC, TaskStatus.SCHEMATIC_REVIEWING));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of());
        ReviewFileRecord file = new ReviewFileRecord();
        file.setUploadedStage(TaskStatus.DRAFT.name());
        file.setUploadedByEmployeeNo("BMS010");
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, FileCategory.SCHEMATIC_REVIEW.name())).thenReturn(List.of(file));
        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.PENDING_FINISH, null,
                "BMS010", "BMS010"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请由设计者在当前评审阶段重新上传终版文件");
    }

    @Test
    void shouldRejectDirectFinishFromReviewStage() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.MUTUAL_CHECK_REVIEWING));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.FINISH, null,
                "BMS001", "BMS001"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前任务状态不允许执行该流程动作");
    }

    @Test
    void shouldStartSchematicReviewAfterMutualOpinionsPassed() {
        when(taskMapper.findById(1001L)).thenReturn(task(ReviewType.SCHEMATIC, TaskStatus.MUTUAL_CHECK_REVIEWING));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of());
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, "SCHEMATIC_REVIEW")).thenReturn(List.of(new ReviewFileRecord()));
        when(taskMapper.update(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(3L);

        WorkflowApplicationService.WorkflowView view = service.transition(1001L, WorkflowAction.START_SCHEMATIC_EXPERT_REVIEW,
                null, "BMS010", "BMS010");

        assertThat(view.toStatus()).isEqualTo(TaskStatus.SCHEMATIC_REVIEWING);
    }

    @Test
    void shouldRejectPcbMutualAssignmentWhenAProcessOpinionIsNotConfirmed() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING));
        ReviewOpinionRecord opinion = new ReviewOpinionRecord();
        opinion.setSourceType(OpinionSourceType.PCB_PROCESS_REVIEW);
        opinion.setStatus("PENDING_CONFIRMATION");
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(opinion));

        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.START_PCB_MATUAL_ASSIGNMENT, null,
                "BMS010", "BMS010"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("专家、工艺或结构评审意见尚未全部确认通过，不能开启互检单分配");
    }

    @Test
    void shouldRequireLatestProcessAndStructureFilesBeforePcbMutualAssignment() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of());
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, "PCB_PROCESS_REVIEW")).thenReturn(List.of(new ReviewFileRecord()));
        when(fileMapper.findLatestByTaskIdAndCategory(1001L, "PCB_STRUCTURE_REVIEW")).thenReturn(List.of());

        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.START_PCB_MATUAL_ASSIGNMENT, null,
                "BMS010", "BMS010"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先上传结构图文件");
    }

    @Test
    void shouldReportMissingTaskWhenStatusUpdateDoesNotPersist() {
        when(taskMapper.findById(1001L)).thenReturn(task(TaskStatus.PENDING_FINISH));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of());
        when(taskMapper.update(any())).thenReturn(0);

        assertThatThrownBy(() -> service.transition(1001L, WorkflowAction.FINISH, null,
                "BMS001", "BMS001"))
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
        task.setDesignerEmployeeNo("BMS010");
        task.setExpertLeaderEmployeeNo("BMS010");
        task.setDesignName("BMS-P1");
        task.setStatus(status.name());
        task.setInitialFileIds("");
        task.setVersion(0L);
        return task;
    }
}
