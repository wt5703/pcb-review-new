package com.bms.task.application;

import com.bms.file.application.FileApplicationService;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.notification.application.ReviewMailNotificationApplicationService;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 验证任务创建、提交、人员激活、查询与流程记录，不依赖真实数据库或白名单资料。
 */
class TaskApplicationServiceTest {
    private static final String DESIGNER_NO = "BMS010";
    private static final String LEADER_NO = "BMS001";

    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper = mock(TaskAssignmentAccessMapper.class);
    private final TaskFlowMapper flowMapper = mock(TaskFlowMapper.class);
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService = mock(ReviewerWhitelistApplicationService.class);
    private final ReviewMailNotificationApplicationService reviewMailNotificationApplicationService = mock(ReviewMailNotificationApplicationService.class);
    private final FileApplicationService fileApplicationService = mock(FileApplicationService.class);
    private final TaskApplicationService service = new TaskApplicationService(taskMapper,
            taskAssignmentAccessMapper, flowMapper, reviewerWhitelistApplicationService,
            reviewMailNotificationApplicationService, fileApplicationService);
    private final CurrentUser designer = new CurrentUser(DESIGNER_NO, "设计者", Set.of(Role.DESIGNER));

    @BeforeEach
    void setUpWhitelistNames() {
        Map<String, String> names = Map.of(
                DESIGNER_NO, "设计者",
                LEADER_NO, "组长",
                "BMS011", "PCB专家",
                "BMS012", "EMC专家",
                "BMS088", "工艺专家");
        when(reviewerWhitelistApplicationService.findDisplayNameOrDefault(anyString(), anyString())).thenAnswer(invocation -> {
            String employeeNo = invocation.getArgument(0);
            String defaultName = invocation.getArgument(1);
            return names.getOrDefault(employeeNo, defaultName);
        });
    }

    @Test
    void shouldCreateTaskWithoutFlowRecord() {
        when(taskMapper.nextId()).thenReturn(101L);
        when(reviewerWhitelistApplicationService.listAssignableUsers(List.of(ReviewerWhitelistRole.PCB_EXPERT)))
                .thenReturn(List.of(assignableReviewer(DESIGNER_NO, "设计者", ReviewerWhitelistRole.PCB_EXPERT)));

        TaskApplicationService.TaskView result = service.saveNewTask(new TaskApplicationService.CreateTaskCommand(
                ReviewType.PCB, "BMS PCB评审", "BMS", DESIGNER_NO, "设计者", "BMS-P1", "BMU", LocalDate.now(),
                LEADER_NO, "组长", List.of(ReviewRole.PCB_EXPERT),
                List.of(new TaskReviewerAssignment(ReviewRole.PCB_EXPERT, List.of(DESIGNER_NO))), null), List.of(), false, designer);

        assertThat(result.id()).isEqualTo(101L);
        verify(taskMapper).insert(any(ReviewTaskRecord.class));
    }

    @Test
    void shouldAppendOutboxEventWhenTaskSubmitted() {
        when(taskMapper.findById(101L)).thenReturn(record(101L, "BMS PCB评审", TaskStatus.DRAFT, 0L));
        when(taskMapper.update(any())).thenReturn(1);
        when(taskMapper.updateAssignedReviewerEmployeeNos(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(1L);

        TaskApplicationService.TaskView result = service.submitTask(101L, List.of(3001L), designer);

        assertThat(result.status()).isEqualTo(TaskStatus.PCB_EXPERT_REVIEWING.name());
        verify(flowMapper).insert(any(TaskFlowRecord.class));
        verify(reviewMailNotificationApplicationService).enqueueTaskCreated(any());
    }

    @Test
    void shouldOnlyActivatePcbAndEmcReviewersWhenPcbTaskIsSubmitted() {
        ReviewTaskRecord task = record(101L, "BMS PCB评审", TaskStatus.DRAFT, 0L);
        task.setReviewRoles("PCB_EXPERT,EMC_EXPERT,PROCESS_EXPERT,STRUCTURE_EXPERT");
        task.setReviewerAssignments("PCB_EXPERT:BMS011;EMC_EXPERT:BMS012;PROCESS_EXPERT:BMS013;STRUCTURE_EXPERT:BMS014");
        when(taskMapper.findById(101L)).thenReturn(task);
        when(taskMapper.update(any())).thenReturn(1);
        when(taskMapper.updateAssignedReviewerEmployeeNos(any())).thenReturn(1);
        when(flowMapper.nextId()).thenReturn(1L);

        service.submitTask(101L, List.of(3001L), designer);

        ArgumentCaptor<ReviewTaskRecord> captor = ArgumentCaptor.forClass(ReviewTaskRecord.class);
        verify(taskMapper).updateAssignedReviewerEmployeeNos(captor.capture());
        assertThat(captor.getValue().getAssignedReviewerEmployeeNos()).isEqualTo("BMS011,BMS012");
    }

    @Test
    void shouldRejectAnotherDesignerSubmittingTask() {
        when(taskMapper.findById(101L)).thenReturn(record(101L, "BMS PCB评审", TaskStatus.DRAFT, 0L));

        assertThatThrownBy(() -> service.submitTask(101L, List.of(3001L),
                new CurrentUser("BMS099", "其他设计者", Set.of(Role.DESIGNER))))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("仅任务设计者可以提交该任务");
    }

    @Test
    void shouldFilterAndPageVisibleTasks() {
        mockTaskPage(2, List.of(
                record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L),
                record(3L, "BMS第二轮评审", TaskStatus.PCB_EXPERT_REVIEWING, 0L)));

        TaskApplicationService.TaskPage page = service.listTasks(new TaskApplicationService.TaskQuery(
                "BMS", null, null, 1, 1, null), administrator());

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(1L);
        verifyNoInteractions(reviewerWhitelistApplicationService);
    }

    @Test
    void shouldMatchKeywordAgainstProjectTaskAndDesignerName() {
        when(taskMapper.countPage(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(1L, 2L);
        when(taskMapper.findPage(any(), any(), any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(record(2L, "原理图任务", TaskStatus.DRAFT, 0L)), List.of(
                        record(1L, "PCB任务", TaskStatus.DRAFT, 0L),
                        record(2L, "原理图任务", TaskStatus.DRAFT, 0L)));

        TaskApplicationService.TaskPage projectPage = service.listTasks(new TaskApplicationService.TaskQuery(
                "VCU", null, null, 1, 20, null), administrator());
        TaskApplicationService.TaskPage designerPage = service.listTasks(new TaskApplicationService.TaskQuery(
                "设计者", null, null, 1, 20, null), administrator());

        assertThat(projectPage.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(2L);
        assertThat(designerPage.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(1L, 2L);
    }

    @Test
    void shouldFilterByMultipleStatuses() {
        mockTaskPage(2, List.of(
                record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L),
                record(3L, "BMS第二轮评审", TaskStatus.PCB_EXPERT_REVIEWING, 0L)));

        TaskApplicationService.TaskPage page = service.listTasks(new TaskApplicationService.TaskQuery(
                null, null, List.of(TaskStatus.DRAFT, TaskStatus.PCB_EXPERT_REVIEWING), 1, 20, null), administrator());

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(1L, 3L);
    }

    @Test
    void shouldIncludeReviewerAssignmentsInTaskListItems() {
        ReviewTaskRecord task = record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L);
        task.setReviewRoles("HARDWARE_EXPERT,EMC_EXPERT");
        task.setReviewerAssignments("HARDWARE_EXPERT:BMS101,BMS102;EMC_EXPERT:BMS103");
        mockTaskPage(1, List.of(task));

        TaskApplicationService.TaskPage page = service.listTasks(null, administrator());

        assertThat(page.items()).singleElement().satisfies(item -> assertThat(item.reviewerAssignments()).containsExactly(
                new TaskReviewerAssignment(ReviewRole.HARDWARE_EXPERT, List.of("BMS101", "BMS102")),
                new TaskReviewerAssignment(ReviewRole.EMC_EXPERT, List.of("BMS103"))));
    }

    @Test
    void shouldOnlyReturnAssignedTasksToProcessExpert() {
        // 数据范围已由 mapper 的 designer/leader/当前待办人员条件限定，服务层不再逐条查询授权表。
        mockTaskPage(1, List.of(record(2L, "VCU原理图评审", TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT, 0L)));

        TaskApplicationService.TaskPage page = service.listTasks(null,
                new CurrentUser("BMS088", "工艺专家", Set.of(Role.PROCESS_EXPERT)));

        assertThat(page.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(2L);
    }

    @Test
    void shouldIncludeTaskFlowRecordsWhenLoadingTaskDetail() {
        when(taskMapper.findById(101L)).thenReturn(record(101L, "BMS PCB评审", TaskStatus.PCB_EXPERT_REVIEWING, 0L));
        TaskFlowRecord flow = new TaskFlowRecord();
        flow.setId(201L);
        flow.setTaskId(101L);
        flow.setAction("CREATE");
        flow.setActionName("创建任务");
        flow.setOperateEmployeeNo(DESIGNER_NO);
        flow.setCreatedAt(LocalDateTime.of(2026, 9, 25, 10, 0));
        when(flowMapper.findByTaskId(101L)).thenReturn(List.of(flow));

        TaskApplicationService.TaskView result = service.getTaskDetail(101L, designer);

        assertThat(result.flowRecords()).singleElement().satisfies(record -> {
            assertThat(record.action()).isEqualTo("CREATE");
            assertThat(record.actionName()).isEqualTo("创建任务");
            assertThat(record.operatorEmployeeNo()).isEqualTo(DESIGNER_NO);
            assertThat(record.operatorName()).isEqualTo("设计者");
        });
    }

    private CurrentUser administrator() {
        return new CurrentUser(LEADER_NO, "组长", Set.of(Role.HARDWARE_DEPARTMENT_MANAGER));
    }

    private void mockTaskPage(long total, List<ReviewTaskRecord> records) {
        when(taskMapper.countPage(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(total);
        when(taskMapper.findPage(any(), any(), any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(records);
    }

    private ReviewerWhitelistApplicationService.AssignableReviewerView assignableReviewer(
            String employeeNo, String displayName, ReviewerWhitelistRole role) {
        return new ReviewerWhitelistApplicationService.AssignableReviewerView(
                employeeNo, displayName, null, null, "研发", role, null);
    }

    private ReviewTaskRecord record(long id, String name, TaskStatus status, long version) {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(id);
        record.setReviewType(id == 2L ? ReviewType.SCHEMATIC.name() : ReviewType.PCB.name());
        record.setTaskName(name);
        record.setProjectName(id == 2L ? "VCU" : "BMS");
        record.setDesignerEmployeeNo(DESIGNER_NO);
        record.setDesignerName("设计者");
        record.setDesignName("设计名称");
        record.setPcbType(id == 2L ? null : "BMU");
        record.setExpectedCompletedDate(LocalDate.of(2026, 10, 1));
        record.setExpertLeaderEmployeeNo(LEADER_NO);
        record.setExpertLeaderName("组长");
        record.setReviewRoles("PCB_EXPERT");
        record.setReviewerAssignments("PCB_EXPERT:BMS011");
        record.setAssignedReviewerEmployeeNos("");
        record.setStatus(status.name());
        record.setInitialFileIds("");
        record.setVersion(version);
        return record;
    }
}
