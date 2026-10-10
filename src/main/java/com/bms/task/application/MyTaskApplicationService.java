package com.bms.task.application;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.task.domain.MyTaskAction;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.review.domain.ReviewRole;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 计算“我的任务”待办清单，只返回当前节点需要用户评审、答复、确认、分配或结束的任务，不混入用户创建或仅可查看的任务。
 */
@Service
public class MyTaskApplicationService {
    private final ReviewTaskMapper taskMapper;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final ReviewOpinionMapper opinionMapper;
    private final UserCenterUserProfileClient userProfileClient;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public MyTaskApplicationService(ReviewTaskMapper taskMapper, TaskAssignmentAccessMapper assignmentAccessMapper,
                                    ReviewOpinionMapper opinionMapper, UserCenterUserProfileClient userProfileClient) {
        this.taskMapper = taskMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.opinionMapper = opinionMapper;
        this.userProfileClient = userProfileClient;
    }

    public List<MyTaskView> list(CurrentUser currentUser) {
        Set<Long> pendingReplyTaskIds = Set.copyOf(opinionMapper.findPendingReplyTaskIdsForDesignerEmployeeNo(currentUser.employeeNo()));
        Set<Long> pendingConfirmationTaskIds = Set.copyOf(opinionMapper.findPendingConfirmationTaskIdsForRaiserEmployeeNo(currentUser.employeeNo()));
        Map<Long, EnumSet<MyTaskAction>> actions = new HashMap<>();
        List<ReviewTaskRecord> unfinishedTasks = taskMapper.findAllUnfinished();
        for (ReviewTaskRecord task : unfinishedTasks) {
            EnumSet<MyTaskAction> taskActions = EnumSet.noneOf(MyTaskAction.class);
            if (assignmentAccessMapper.isCurrentTaskProcessor(task.getId(), currentUser.employeeNo())) {
                taskActions.add(MyTaskAction.REVIEW);
            }
            if (pendingReplyTaskIds.contains(task.getId())) {
                taskActions.add(MyTaskAction.REPLY_OPINION);
            }
            if (pendingConfirmationTaskIds.contains(task.getId())) {
                taskActions.add(MyTaskAction.CONFIRM_OPINION);
            }
            addManagementAction(task, currentUser, taskActions);
            if (!taskActions.isEmpty()) {
                actions.put(task.getId(), taskActions);
            }
        }
        return unfinishedTasks.stream().filter(task -> actions.containsKey(task.getId()))
                .map(task -> toView(task, actions.get(task.getId()))).toList();
    }

    private MyTaskView toView(ReviewTaskRecord task, EnumSet<MyTaskAction> actions) {
        return MyTaskView.from(task, userProfileClient.getByEmployeeNo(task.getDesignerEmployeeNo()).displayName(),
                userProfileClient.getByEmployeeNo(task.getExpertLeaderEmployeeNo()).displayName(), actions);
    }

    private void addManagementAction(ReviewTaskRecord task, CurrentUser currentUser, EnumSet<MyTaskAction> actions) {
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        ReviewType type = ReviewType.valueOf(task.getReviewType());
        if (type == ReviewType.PCB && status == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT
                && permissionPolicy.has(currentUser.roles(), Permission.ASSIGN_PCB_MUTUAL_CHECK)) {
            actions.add(MyTaskAction.ASSIGN_REVIEWERS);
        }
        if (type == ReviewType.SCHEMATIC && status == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT
                && permissionPolicy.has(currentUser.roles(), Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK)) {
            actions.add(MyTaskAction.ASSIGN_REVIEWERS);
        }
        if (((type == ReviewType.PCB && status == TaskStatus.MUTUAL_CHECK_REVIEWING)
                || (type == ReviewType.SCHEMATIC && status == TaskStatus.SCHEMATIC_REVIEWING))
                && permissionPolicy.has(currentUser.roles(), type == ReviewType.PCB ? Permission.FINISH_PCB_TASK : Permission.FINISH_SCHEMATIC_TASK)) {
            actions.add(MyTaskAction.FINISH_TASK);
        }
    }

    public record MyTaskView(Long id, ReviewType reviewType, String taskName, String projectName, String designerEmployeeNo, String designerName,
                             String designName, String pcbType, LocalDate expectedCompletedDate, String expertLeaderEmployeeNo, String expertLeaderName,
                             List<ReviewRole> reviewRoles, String reviewDescription, TaskStatus status, List<MyTaskAction> actions) {
        static MyTaskView from(ReviewTaskRecord record, String designerName, String expertLeaderName, EnumSet<MyTaskAction> actions) {
            List<ReviewRole> reviewRoles = record.getReviewRoles() == null || record.getReviewRoles().isBlank() ? List.of()
                    : java.util.Arrays.stream(record.getReviewRoles().split(",")).map(ReviewRole::valueOf).toList();
            return new MyTaskView(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getTaskName(), record.getProjectName(),
                    record.getDesignerEmployeeNo(), designerName, record.getDesignName(), record.getPcbType(), record.getExpectedCompletedDate(),
                    record.getExpertLeaderEmployeeNo(), expertLeaderName, reviewRoles, record.getReviewDescription(),
                    TaskStatus.valueOf(record.getStatus()), List.copyOf(actions));
        }
    }
}
