package com.bms.task.application;

import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.task.domain.MyTaskAction;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskReviewerAssignmentCodec;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.review.domain.ReviewRole;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 计算“我的任务”待办清单，只返回当前节点需要用户评审、提交流程、答复、确认、分配或结束的任务，不混入用户创建或仅可查看的任务。
 */
@Service
public class MyTaskApplicationService {
    private final ReviewTaskMapper taskMapper;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final ReviewOpinionMapper opinionMapper;
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService;

    public MyTaskApplicationService(ReviewTaskMapper taskMapper, TaskAssignmentAccessMapper assignmentAccessMapper,
                                    ReviewOpinionMapper opinionMapper,
                                    ReviewerWhitelistApplicationService reviewerWhitelistApplicationService) {
        this.taskMapper = taskMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.opinionMapper = opinionMapper;
        this.reviewerWhitelistApplicationService = reviewerWhitelistApplicationService;
    }

    public List<MyTaskView> list(String operatorEmployeeNo, String operatorName) {
        Set<Long> pendingReplyTaskIds = Set.copyOf(opinionMapper.findPendingReplyTaskIdsForDesignerEmployeeNo(operatorEmployeeNo));
        Set<Long> pendingConfirmationTaskIds = Set.copyOf(opinionMapper.findPendingConfirmationTaskIdsForRaiserEmployeeNo(operatorEmployeeNo));
        Map<Long, EnumSet<MyTaskAction>> actions = new HashMap<>();
        List<ReviewTaskRecord> unfinishedTasks = taskMapper.findAllUnfinished();
        Map<String, String> reviewerNames = reviewerWhitelistApplicationService.findDisplayNamesByEmployeeNos(
                unfinishedTasks.stream()
                        .flatMap(task -> TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments()).stream())
                        .flatMap(assignment -> assignment.reviewerEmployeeNos().stream())
                        .distinct()
                        .toList());
        for (ReviewTaskRecord task : unfinishedTasks) {
            EnumSet<MyTaskAction> taskActions = EnumSet.noneOf(MyTaskAction.class);
            if (assignmentAccessMapper.isCurrentTaskProcessor(task.getId(), operatorEmployeeNo)) {
                taskActions.add(MyTaskAction.REVIEW);
            }
            if (pendingReplyTaskIds.contains(task.getId())) {
                taskActions.add(MyTaskAction.REPLY_OPINION);
            }
            if (pendingConfirmationTaskIds.contains(task.getId())) {
                taskActions.add(MyTaskAction.CONFIRM_OPINION);
            }
            addDesignerWorkflowSubmissionAction(task, operatorEmployeeNo, taskActions);
            addManagementAction(task, operatorEmployeeNo, operatorName, taskActions);
            if (!taskActions.isEmpty()) {
                actions.put(task.getId(), taskActions);
            }
        }
        return unfinishedTasks.stream().filter(task -> actions.containsKey(task.getId()))
                .map(task -> toView(task, actions.get(task.getId()), reviewerNames)).toList();
    }

    /**
     * 工作台卡片统计复用“我的待办”的任务范围，保证统计数字与待办列表一致。
     * 本月已结束以任务最终状态写入时间为准，并只统计当前设计者创建的任务。
     */
    public WorkbenchTaskStatistics statistics(String operatorEmployeeNo) {
        List<MyTaskView> myTasks = list(operatorEmployeeNo, null);
        int reviewingTaskCount = (int) myTasks.stream()
                .filter(task -> task.actions().contains(MyTaskAction.REVIEW))
                .count();
        int pendingReplyTaskCount = (int) myTasks.stream()
                .filter(task -> task.actions().contains(MyTaskAction.REPLY_OPINION))
                .count();
        int pendingConfirmationTaskCount = (int) myTasks.stream()
                .filter(task -> task.actions().contains(MyTaskAction.CONFIRM_OPINION))
                .count();
        LocalDate firstDayOfMonth = LocalDate.now().withDayOfMonth(1);
        long finishedThisMonthCount = taskMapper.countFinishedByDesignerEmployeeNoInPeriod(operatorEmployeeNo,
                firstDayOfMonth.atStartOfDay(), firstDayOfMonth.plusMonths(1).atStartOfDay());
        return new WorkbenchTaskStatistics(myTasks.size(), reviewingTaskCount, pendingReplyTaskCount,
                pendingConfirmationTaskCount, finishedThisMonthCount);
    }

    private MyTaskView toView(ReviewTaskRecord task, EnumSet<MyTaskAction> actions, Map<String, String> reviewerNames) {
        return MyTaskView.from(task,
                reviewerWhitelistApplicationService.findDisplayNameOrDefault(task.getDesignerEmployeeNo(), task.getDesignerName()),
                reviewerWhitelistApplicationService.findDisplayNameOrDefault(task.getExpertLeaderEmployeeNo(), task.getExpertLeaderName()),
                TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments()).stream()
                        .map(assignment -> TaskApplicationService.ReviewerAssignmentView.from(assignment, reviewerNames))
                        .toList(), actions);
    }

    /** PCB、原理图的指定评审阶段均由设计者继续提交对应的流程动作。 */
    private void addDesignerWorkflowSubmissionAction(ReviewTaskRecord task, String operatorEmployeeNo,
                                                     EnumSet<MyTaskAction> actions) {
        if (!operatorEmployeeNo.equals(task.getDesignerEmployeeNo())) {
            return;
        }
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        if ((ReviewType.SCHEMATIC.name().equals(task.getReviewType())
                && (status == TaskStatus.MUTUAL_CHECK_REVIEWING || status == TaskStatus.SCHEMATIC_REVIEWING))
                || (ReviewType.PCB.name().equals(task.getReviewType())
                && (status == TaskStatus.PCB_EXPERT_REVIEWING || status == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING))) {
            actions.add(MyTaskAction.SUBMIT_WORKFLOW);
        }
    }

    private void addManagementAction(ReviewTaskRecord task, String operatorEmployeeNo, String operatorName, EnumSet<MyTaskAction> actions) {
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        // 不再按角色识别管理待办；只向任务记录中指定的组长展示。
        if (!operatorEmployeeNo.equals(task.getExpertLeaderEmployeeNo())) {
            return;
        }
        if (status == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT) {
            actions.add(MyTaskAction.ASSIGN_REVIEWERS);
        }
        if (status == TaskStatus.PENDING_FINISH) {
            actions.add(MyTaskAction.FINISH_TASK);
        }
    }

    public record MyTaskView(Long id, ReviewType reviewType, String taskName, String projectName, String designerEmployeeNo, String designerName,
                             String designName, String pcbType, LocalDate expectedCompletedDate, String expertLeaderEmployeeNo, String expertLeaderName,
                             List<ReviewRole> reviewRoles, List<TaskApplicationService.ReviewerAssignmentView> reviewerAssignments,
                             String reviewDescription, TaskStatus status, List<MyTaskAction> actions,
                             @Schema(description = "任务创建时间，格式 yyyy-MM-dd HH:mm:ss", example = "2026-10-19 15:30:00")
                             @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdTime) {
        static MyTaskView from(ReviewTaskRecord record, String designerName, String expertLeaderName,
                               List<TaskApplicationService.ReviewerAssignmentView> reviewerAssignments,
                               EnumSet<MyTaskAction> actions) {
            List<ReviewRole> reviewRoles = record.getReviewRoles() == null || record.getReviewRoles().isBlank() ? List.of()
                    : java.util.Arrays.stream(record.getReviewRoles().split(",")).map(ReviewRole::valueOf).toList();
            return new MyTaskView(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getTaskName(), record.getProjectName(),
                    record.getDesignerEmployeeNo(), designerName, record.getDesignName(), record.getPcbType(), record.getExpectedCompletedDate(),
                    record.getExpertLeaderEmployeeNo(), expertLeaderName, reviewRoles, List.copyOf(reviewerAssignments), record.getReviewDescription(),
                    TaskStatus.valueOf(record.getStatus()), List.copyOf(actions), record.getCreatedAt());
        }
    }

    public record WorkbenchTaskStatistics(int myTaskCount, int reviewingTaskCount, int pendingReplyTaskCount,
                                          int pendingConfirmationTaskCount, long finishedThisMonthCount) { }
}
