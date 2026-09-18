package com.leapmotor.pcbreview.task.application;

import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionMapper;
import com.leapmotor.pcbreview.task.domain.MyTaskAction;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public MyTaskApplicationService(ReviewTaskMapper taskMapper, TaskAssignmentAccessMapper assignmentAccessMapper,
                                    ReviewOpinionMapper opinionMapper) {
        this.taskMapper = taskMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.opinionMapper = opinionMapper;
    }

    public List<MyTaskView> list(CurrentUser currentUser) {
        Set<Long> pendingReplyTaskIds = Set.copyOf(opinionMapper.findPendingReplyTaskIdsForDesigner(currentUser.id()));
        Set<Long> pendingConfirmationTaskIds = Set.copyOf(opinionMapper.findPendingConfirmationTaskIdsForRaiser(currentUser.id()));
        Map<Long, EnumSet<MyTaskAction>> actions = new HashMap<>();
        for (ReviewTaskRecord task : taskMapper.findAll()) {
            EnumSet<MyTaskAction> taskActions = EnumSet.noneOf(MyTaskAction.class);
            if (assignmentAccessMapper.isCurrentTaskProcessor(task.getId(), currentUser.id())) {
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
        return taskMapper.findAll().stream().filter(task -> actions.containsKey(task.getId()))
                .map(task -> MyTaskView.from(task, actions.get(task.getId()))).toList();
    }

    private void addManagementAction(ReviewTaskRecord task, CurrentUser currentUser, EnumSet<MyTaskAction> actions) {
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        ReviewType type = ReviewType.valueOf(task.getReviewType());
        boolean isDesigner = task.getDesignerId().equals(currentUser.id());
        if (isDesigner && type == ReviewType.PCB && status == TaskStatus.PCB_PENDING_REVIEW
                && permissionPolicy.has(currentUser.roles(), Permission.ASSIGN_PCB_EXPERT)) {
            actions.add(MyTaskAction.ASSIGN_REVIEWERS);
        }
        if (type == ReviewType.PCB && status == TaskStatus.PENDING_MUTUAL_ASSIGNMENT
                && permissionPolicy.has(currentUser.roles(), Permission.ASSIGN_PCB_MUTUAL_CHECK)) {
            actions.add(MyTaskAction.ASSIGN_REVIEWERS);
        }
        if (type == ReviewType.SCHEMATIC && (status == TaskStatus.SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT || status == TaskStatus.SCHEMATIC_PENDING_REVIEW)
                && (permissionPolicy.has(currentUser.roles(), Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK)
                || permissionPolicy.has(currentUser.roles(), Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT))) {
            actions.add(MyTaskAction.ASSIGN_REVIEWERS);
        }
        if (status == TaskStatus.PENDING_FINISH_CONFIRMATION
                && permissionPolicy.has(currentUser.roles(), type == ReviewType.PCB ? Permission.FINISH_PCB_TASK : Permission.FINISH_SCHEMATIC_TASK)) {
            actions.add(MyTaskAction.FINISH_TASK);
        }
    }

    public record MyTaskView(Long id, String taskName, ReviewType reviewType, TaskStatus status, List<MyTaskAction> actions) {
        static MyTaskView from(ReviewTaskRecord record, EnumSet<MyTaskAction> actions) {
            return new MyTaskView(record.getId(), record.getTaskName(), ReviewType.valueOf(record.getReviewType()),
                    TaskStatus.valueOf(record.getStatus()), List.copyOf(actions));
        }
    }
}
