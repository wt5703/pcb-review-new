package com.bms.task.application;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.application.ReviewMailNotificationApplicationService;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.task.domain.TaskReviewerAssignment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * @date 2026-09-14
 * @description 验证评审任务应用服务在创建、提交和分页筛选时协调持久化、流程记录与 Outbox 的行为，不依赖真实数据库。
 */
class TaskApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper = mock(TaskAssignmentAccessMapper.class);
    private final TaskFlowMapper flowMapper = mock(TaskFlowMapper.class);
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService = mock(ReviewerWhitelistApplicationService.class);
    private final ReviewMailNotificationApplicationService reviewMailNotificationApplicationService = mock(ReviewMailNotificationApplicationService.class);
    private final TaskApplicationService service = new TaskApplicationService(taskMapper,
            taskAssignmentAccessMapper, flowMapper, reviewerWhitelistApplicationService,
            reviewMailNotificationApplicationService);
    private final CurrentUser designer = new CurrentUser(10L, Set.of(Role.DESIGNER));

    @Test
    void shouldCreateTaskWithoutFlowRecord() {
        when(taskMapper.nextId()).thenReturn(101L);
        when(reviewerWhitelistApplicationService.listAssignableUsers(List.of(ReviewerWhitelistRole.PCB_EXPERT))).thenReturn(List.of(
                new ReviewerWhitelistApplicationService.AssignableReviewerView(10L, "BMS010", "设计者", "研发", ReviewerWhitelistRole.PCB_EXPERT)));

        TaskApplicationService.TaskView result = service.create(new TaskApplicationService.CreateTaskCommand(
                ReviewType.PCB, "BMS PCB评审", "BMS", 10L, "设计者", "BMS-P1", "BMU", java.time.LocalDate.now(),
                10L, "设计者", List.of(ReviewRole.PCB_EXPERT),
                List.of(new TaskReviewerAssignment(ReviewRole.PCB_EXPERT, List.of(10L))), null), designer);

        assertThat(result.id()).isEqualTo(101L);
        verify(taskMapper).insert(any(ReviewTaskRecord.class));
    }

    @Test
    void shouldAppendOutboxEventWhenTaskSubmitted() {
        when(taskMapper.findById(101L)).thenReturn(record(101L, "BMS PCB评审", TaskStatus.DRAFT, 0L));
        when(taskMapper.update(any())).thenReturn(1);
        when(taskMapper.updateAssignedReviewerIds(any())).thenReturn(1);

        TaskApplicationService.TaskView result = service.submit(101L, List.of(3001L), designer);

        assertThat(result.status()).isEqualTo(TaskStatus.PCB_EXPERT_REVIEWING.name());
        verify(flowMapper).insert(any());
        verify(reviewMailNotificationApplicationService).enqueueTaskCreated(any());
    }

    @Test
    void shouldOnlyActivatePcbAndEmcReviewersWhenPcbTaskIsSubmitted() {
        ReviewTaskRecord task = record(101L, "BMS PCB评审", TaskStatus.DRAFT, 0L);
        task.setReviewRoles("PCB_EXPERT,EMC_EXPERT,PROCESS_EXPERT,STRUCTURE_EXPERT");
        task.setReviewerAssignments("PCB_EXPERT:11;EMC_EXPERT:12;PROCESS_EXPERT:13;STRUCTURE_EXPERT:14");
        when(taskMapper.findById(101L)).thenReturn(task);
        when(taskMapper.update(any())).thenReturn(1);
        when(taskMapper.updateAssignedReviewerIds(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(1L);

        service.submit(101L, List.of(3001L), designer);

        ArgumentCaptor<ReviewTaskRecord> captor = ArgumentCaptor.forClass(ReviewTaskRecord.class);
        verify(taskMapper).updateAssignedReviewerIds(captor.capture());
        assertThat(captor.getValue().getAssignedReviewerIds()).isEqualTo("11,12");
    }

    @Test
    void shouldRejectAnotherDesignerSubmittingTask() {
        when(taskMapper.findById(101L)).thenReturn(record(101L, "BMS PCB评审", TaskStatus.DRAFT, 0L));

        assertThatThrownBy(() -> service.submit(101L, List.of(3001L), new CurrentUser(11L, Set.of(Role.DESIGNER))))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("仅任务设计者可以提交该任务");
    }

    @Test
    void shouldFilterAndPageVisibleTasks() {
        when(taskMapper.findAll()).thenReturn(List.of(
                record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L),
                record(2L, "VCU原理图评审", TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT, 0L),
                record(3L, "BMS第二轮评审", TaskStatus.PCB_EXPERT_REVIEWING, 0L)));

        TaskApplicationService.TaskPage page = service.list(new TaskApplicationService.TaskQuery(
                "BMS", null, null, 1, 1, null), new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(1L);
    }

    @Test
    void shouldMatchKeywordAgainstProjectTaskAndDesignerName() {
        when(taskMapper.findAll()).thenReturn(List.of(
                record(1L, "PCB任务", TaskStatus.DRAFT, 0L),
                record(2L, "原理图任务", TaskStatus.DRAFT, 0L)));

        TaskApplicationService.TaskPage projectPage = service.list(new TaskApplicationService.TaskQuery(
                "VCU", null, null, 1, 20, null), new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));
        TaskApplicationService.TaskPage designerPage = service.list(new TaskApplicationService.TaskQuery(
                "设计者#10", null, null, 1, 20, null), new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(projectPage.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(2L);
        assertThat(designerPage.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(1L, 2L);
    }

    @Test
    void shouldFilterByMultipleStatuses() {
        when(taskMapper.findAll()).thenReturn(List.of(
                record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L),
                record(2L, "VCU原理图评审", TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT, 0L),
                record(3L, "BMS第二轮评审", TaskStatus.PCB_EXPERT_REVIEWING, 0L)));

        TaskApplicationService.TaskPage page = service.list(new TaskApplicationService.TaskQuery(
                null, null, List.of(TaskStatus.DRAFT, TaskStatus.PCB_EXPERT_REVIEWING), 1, 20, null),
                new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(1L, 3L);
    }

    @Test
    void shouldIncludeReviewerAssignmentsInTaskListItems() {
        ReviewTaskRecord task = record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L);
        task.setReviewRoles("HARDWARE_EXPERT,EMC_EXPERT");
        task.setReviewerAssignments("HARDWARE_EXPERT:101,102;EMC_EXPERT:103");
        when(taskMapper.findAll()).thenReturn(List.of(task));

        TaskApplicationService.TaskPage page = service.list(null,
                new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(page.items()).singleElement().satisfies(item -> assertThat(item.reviewerAssignments()).containsExactly(
                        new TaskReviewerAssignment(ReviewRole.HARDWARE_EXPERT, List.of(101L, 102L)),
                        new TaskReviewerAssignment(ReviewRole.EMC_EXPERT, List.of(103L))));
    }

    @Test
    void shouldOnlyReturnAssignedTasksToProcessExpert() {
        when(taskMapper.findAll()).thenReturn(List.of(
                record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L),
                record(2L, "VCU原理图评审", TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT, 0L)));
        when(taskAssignmentAccessMapper.isAssignedToTask(1L, 88L)).thenReturn(false);
        when(taskAssignmentAccessMapper.isAssignedToTask(2L, 88L)).thenReturn(true);

        TaskApplicationService.TaskPage page = service.list(null, new CurrentUser(88L, Set.of(Role.PROCESS_EXPERT)));

        assertThat(page.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(2L);
    }

    @Test
    void shouldIncludeTaskFlowRecordsWhenLoadingTaskDetail() {
        when(taskMapper.findById(101L)).thenReturn(record(101L, "BMS PCB评审", TaskStatus.PCB_EXPERT_REVIEWING, 0L));
        com.bms.workflow.infrastructure.TaskFlowRecord flow = new com.bms.workflow.infrastructure.TaskFlowRecord();
        flow.setId(201L); flow.setTaskId(101L); flow.setAction("CREATE"); flow.setActionName("创建任务"); flow.setOperateId(10L);
        flow.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 25, 10, 0));
        when(flowMapper.findByTaskId(101L)).thenReturn(List.of(flow));

        TaskApplicationService.TaskView result = service.get(101L, designer);

        assertThat(result.flowRecords()).singleElement().satisfies(record -> {
            assertThat(record.action()).isEqualTo("CREATE");
            assertThat(record.actionName()).isEqualTo("创建任务");
            assertThat(record.operatorName()).isEqualTo("用户#10");
        });
    }

    private ReviewTaskRecord record(long id, String name, TaskStatus status, long version) {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(id);
        record.setReviewType(id == 2L ? ReviewType.SCHEMATIC.name() : ReviewType.PCB.name());
        record.setTaskName(name);
        record.setProjectName(id == 2L ? "VCU" : "BMS");
        record.setDesignerId(10L);
        record.setDesignName("设计");
        record.setPcbType(id == 2L ? null : "BMU");
        record.setStatus(status.name());
        record.setInitialFileIds("");
        record.setVersion(version);
        return record;
    }
}
