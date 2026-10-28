package com.bms.task.application;

import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.task.domain.MyTaskAction;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.domain.TaskReviewerAssignmentCodec;
import com.bms.task.domain.TaskStatus;
import com.bms.review.domain.ReviewRole;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 验证“我的任务”只聚合当前节点需要用户操作的评审、答复、确认、分配和结束事项，不返回用户仅可查看的任务。
 */
class MyTaskApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskAssignmentAccessMapper assignmentAccessMapper = mock(TaskAssignmentAccessMapper.class);
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService = mock(ReviewerWhitelistApplicationService.class);
    private final MyTaskApplicationService service = new MyTaskApplicationService(taskMapper, assignmentAccessMapper, opinionMapper,
            reviewerWhitelistApplicationService);

    @Test
    void shouldOnlyReturnTaskRequiringCurrentReviewerAction() {
        ReviewTaskRecord completedTask = task(1L, "BMS010", TaskStatus.PCB_EXPERT_REVIEWING);
        ReviewTaskRecord myTask = task(2L, "BMS010", TaskStatus.MUTUAL_CHECK_REVIEWING);
        myTask.setCreatedAt(LocalDateTime.of(2026, 10, 19, 15, 30));
        myTask.setReviewerAssignments(TaskReviewerAssignmentCodec.encode(List.of(
                new TaskReviewerAssignment(ReviewRole.PCB_EXPERT, List.of("BMS099")))));
        when(taskMapper.findAllUnfinished()).thenReturn(List.of(completedTask, myTask));
        when(assignmentAccessMapper.isCurrentTaskProcessor(1L, "BMS088")).thenReturn(false);
        when(assignmentAccessMapper.isCurrentTaskProcessor(2L, "BMS088")).thenReturn(true);
        when(reviewerWhitelistApplicationService.findDisplayNamesByEmployeeNos(List.of("BMS099")))
                .thenReturn(Map.of("BMS099", "评审工程师"));

        List<MyTaskApplicationService.MyTaskView> tasks = service.list("BMS088", "BMS088");

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).id()).isEqualTo(2L);
        assertThat(tasks.get(0).actions()).containsExactly(MyTaskAction.REVIEW);
        assertThat(tasks.get(0).createdTime()).isEqualTo(LocalDateTime.of(2026, 10, 19, 15, 30));
        assertThat(tasks.get(0).reviewerAssignments()).singleElement().satisfies(assignment -> {
            assertThat(assignment.reviewRole()).isEqualTo(ReviewRole.PCB_EXPERT);
            assertThat(assignment.reviewRoleName()).isEqualTo("PCB评审");
            assertThat(assignment.reviewerEmployeeNos()).containsExactly("BMS099");
            assertThat(assignment.reviewers()).singleElement().satisfies(reviewer -> {
                assertThat(reviewer.employeeNo()).isEqualTo("BMS099");
                assertThat(reviewer.reviewerName()).isEqualTo("评审工程师");
            });
        });
    }

    @Test
    void shouldNotReturnTaskAfterReviewerCompletesCurrentStage() {
        when(taskMapper.findAllUnfinished()).thenReturn(
                List.of(task(1L, "BMS010", TaskStatus.PCB_EXPERT_REVIEWING)));
        // 无意见确认提交会把该人员从 assigned_reviewer_employee_nos 移除，访问查询因此返回 false。
        when(assignmentAccessMapper.isCurrentTaskProcessor(1L, "BMS020")).thenReturn(false);
        when(opinionMapper.findPendingReplyTaskIdsForDesignerEmployeeNo("BMS020")).thenReturn(List.of());
        when(opinionMapper.findPendingConfirmationTaskIdsForRaiserEmployeeNo("BMS020")).thenReturn(List.of());

        List<MyTaskApplicationService.MyTaskView> tasks = service.list("BMS020", "张工");

        assertThat(tasks).isEmpty();
    }

    @Test
    void shouldIncludeDesignerReplyAndLeaderFinishActions() {
        when(taskMapper.findAllUnfinished()).thenReturn(
                List.of(task(1L, "BMS010", TaskStatus.MUTUAL_CHECK_REVIEWING), task(2L, "BMS099", TaskStatus.MUTUAL_CHECK_REVIEWING)),
                List.of(task(2L, "BMS099", TaskStatus.PENDING_FINISH)));
        when(opinionMapper.findPendingReplyTaskIdsForDesignerEmployeeNo("BMS010")).thenReturn(List.of(1L));

        List<MyTaskApplicationService.MyTaskView> designerTasks = service.list("BMS010", "BMS010");
        assertThat(designerTasks.get(0).actions()).contains(MyTaskAction.REPLY_OPINION);

        List<MyTaskApplicationService.MyTaskView> leaderTasks = service.list("BMS001", "BMS001");
        assertThat(leaderTasks).extracting(MyTaskApplicationService.MyTaskView::id).containsExactly(2L);
        assertThat(leaderTasks.get(0).actions()).containsExactly(MyTaskAction.FINISH_TASK);
    }

    @Test
    void shouldIncludeSchematicTasksForDesignerWhenWorkflowSubmissionIsRequired() {
        ReviewTaskRecord mutualCheckTask = task(1L, "BMS010", TaskStatus.MUTUAL_CHECK_REVIEWING);
        mutualCheckTask.setReviewType(ReviewType.SCHEMATIC.name());
        ReviewTaskRecord schematicReviewTask = task(2L, "BMS010", TaskStatus.SCHEMATIC_REVIEWING);
        schematicReviewTask.setReviewType(ReviewType.SCHEMATIC.name());
        when(taskMapper.findAllUnfinished()).thenReturn(List.of(mutualCheckTask, schematicReviewTask));

        List<MyTaskApplicationService.MyTaskView> tasks = service.list("BMS010", "设计者");

        assertThat(tasks).extracting(MyTaskApplicationService.MyTaskView::id).containsExactly(1L, 2L);
        assertThat(tasks).allSatisfy(task -> assertThat(task.actions()).containsExactly(MyTaskAction.SUBMIT_WORKFLOW));
    }

    @Test
    void shouldIncludePcbTasksForDesignerWhenWorkflowSubmissionIsRequired() {
        ReviewTaskRecord expertReviewTask = task(1L, "BMS010", TaskStatus.PCB_EXPERT_REVIEWING);
        ReviewTaskRecord processStructureReviewTask = task(2L, "BMS010", TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING);
        when(taskMapper.findAllUnfinished()).thenReturn(List.of(expertReviewTask, processStructureReviewTask));

        List<MyTaskApplicationService.MyTaskView> tasks = service.list("BMS010", "设计者");

        assertThat(tasks).extracting(MyTaskApplicationService.MyTaskView::id).containsExactly(1L, 2L);
        assertThat(tasks).allSatisfy(task -> assertThat(task.actions()).containsExactly(MyTaskAction.SUBMIT_WORKFLOW));
    }

    @Test
    void shouldSummarizeCurrentUsersWorkbenchTasks() {
        when(taskMapper.findAllUnfinished()).thenReturn(List.of(
                task(1L, "BMS010", TaskStatus.MUTUAL_CHECK_REVIEWING),
                task(2L, "BMS010", TaskStatus.MUTUAL_CHECK_REVIEWING),
                task(3L, "BMS010", TaskStatus.MUTUAL_CHECK_REVIEWING)));
        when(assignmentAccessMapper.isCurrentTaskProcessor(1L, "BMS010")).thenReturn(true);
        when(opinionMapper.findPendingReplyTaskIdsForDesignerEmployeeNo("BMS010")).thenReturn(List.of(2L));
        when(opinionMapper.findPendingConfirmationTaskIdsForRaiserEmployeeNo("BMS010")).thenReturn(List.of(3L));
        when(taskMapper.countFinishedByDesignerEmployeeNoInPeriod(org.mockito.ArgumentMatchers.eq("BMS010"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(16L);

        MyTaskApplicationService.WorkbenchTaskStatistics statistics = service.statistics("BMS010");

        assertThat(statistics.myTaskCount()).isEqualTo(3);
        assertThat(statistics.reviewingTaskCount()).isEqualTo(1);
        assertThat(statistics.pendingReplyTaskCount()).isEqualTo(1);
        assertThat(statistics.pendingConfirmationTaskCount()).isEqualTo(1);
        assertThat(statistics.finishedThisMonthCount()).isEqualTo(16);
    }

    private ReviewTaskRecord task(long id, String designerEmployeeNo, TaskStatus status) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(id);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("我的任务测试" + id);
        task.setDesignerEmployeeNo(designerEmployeeNo);
        task.setDesignerName(designerEmployeeNo);
        task.setExpertLeaderEmployeeNo("BMS001");
        task.setExpertLeaderName("BMS001");
        task.setStatus(status.name());
        return task;
    }
}
