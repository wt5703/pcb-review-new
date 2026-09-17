package com.leapmotor.pcbreview.workflow.application;

import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditMapper;
import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditRecord;
import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileMapper;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventMapper;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventRecord;
import com.leapmotor.pcbreview.review.application.TaskCheckItemApplicationService;
import com.leapmotor.pcbreview.review.domain.OpinionStatus;
import com.leapmotor.pcbreview.review.domain.ReviewerProcessStatus;
import com.leapmotor.pcbreview.review.domain.ReviewerProgress;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerRecord;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import com.leapmotor.pcbreview.workflow.domain.CompletionPolicy;
import com.leapmotor.pcbreview.workflow.domain.WorkflowAction;
import com.leapmotor.pcbreview.workflow.infrastructure.TaskFlowMapper;
import com.leapmotor.pcbreview.workflow.infrastructure.TaskFlowRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 编排 PCB 和原理图评审任务的阶段推进与最终结束，在同一事务内重新计算多人完成条件、校验最新文件、冻结检查项快照并记录流程审计事件。
 */
@Service
public class WorkflowApplicationService {
    private final ReviewTaskMapper taskMapper;
    private final TaskReviewerMapper reviewerMapper;
    private final ReviewOpinionMapper opinionMapper;
    private final ReviewFileMapper fileMapper;
    private final TaskCheckItemApplicationService checkItemApplicationService;
    private final TaskFlowMapper flowMapper;
    private final OperationAuditMapper auditMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final CompletionPolicy completionPolicy = new CompletionPolicy();
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public WorkflowApplicationService(ReviewTaskMapper taskMapper, TaskReviewerMapper reviewerMapper, ReviewOpinionMapper opinionMapper,
                                      ReviewFileMapper fileMapper, TaskCheckItemApplicationService checkItemApplicationService, TaskFlowMapper flowMapper,
                                      OperationAuditMapper auditMapper, OutboxEventMapper outboxEventMapper) {
        this.taskMapper = taskMapper;
        this.reviewerMapper = reviewerMapper;
        this.opinionMapper = opinionMapper;
        this.fileMapper = fileMapper;
        this.checkItemApplicationService = checkItemApplicationService;
        this.flowMapper = flowMapper;
        this.auditMapper = auditMapper;
        this.outboxEventMapper = outboxEventMapper;
    }

    @Transactional
    public WorkflowView transition(long taskId, WorkflowAction action, long version, String comment, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTask(taskId);
        if (TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许重新打开或流转");
        }
        TaskStatus target = targetStatus(task, action, currentUser);
        if (requiresAllReviewersCompleted(action) && !allActiveReviewersCompleted(taskId)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, action == WorkflowAction.REQUEST_FINISH
                    ? "仍存在未完成人员或未闭环意见，不能申请结束" : "仍存在未完成人员或未闭环意见，不能进入下一阶段");
        }
        if (action == WorkflowAction.FINISH) {
            if (!allActiveReviewersCompleted(taskId)) {
                throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "仍存在未完成人员或未闭环意见，不能结束任务");
            }
            if (fileMapper.findLatestByTaskId(taskId).isEmpty()) {
                throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "任务结束前必须存在最新设计文件");
            }
            checkItemApplicationService.materializeActiveTaskItems(taskId);
        }
        if (task.getVersion() != version) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        String fromStatus = task.getStatus();
        task.setStatus(target.name());
        if (taskMapper.update(task) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "任务状态已被其他操作更新，请刷新后重试");
        }
        appendHistory(taskId, fromStatus, target.name(), action, currentUser.id(), comment);
        auditMapper.insert(new OperationAuditRecord("REVIEW_TASK", taskId, "WORKFLOW_" + action.name(), currentUser.id(),
                fromStatus + " -> " + target.name()));
        outboxEventMapper.insert(new OutboxEventRecord("TASK_STATUS_CHANGED", "REVIEW_TASK", taskId,
                "{\"taskId\":" + taskId + ",\"action\":\"" + action.name() + "\",\"toStatus\":\"" + target.name() + "\"}", "PENDING"));
        return new WorkflowView(taskId, TaskStatus.valueOf(fromStatus), target, version + 1);
    }

    private TaskStatus targetStatus(ReviewTaskRecord task, WorkflowAction action, CurrentUser currentUser) {
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        return switch (action) {
            case START_PCB_EXPERT_REVIEW -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.PCB_PENDING_REVIEW,
                    TaskStatus.PCB_EXPERT_REVIEWING, currentUser, Permission.ASSIGN_PCB_EXPERT);
            case START_PCB_OPTIONAL_REVIEW -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.PCB_EXPERT_REVIEWING,
                    TaskStatus.PCB_OPTIONAL_REVIEWING, currentUser, Permission.ASSIGN_PROCESS_EXPERT);
            case PREPARE_PCB_MUTUAL_ASSIGNMENT -> requireTransition(reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_EXPERT_REVIEWING || current == TaskStatus.PCB_OPTIONAL_REVIEWING),
                    TaskStatus.PENDING_MUTUAL_ASSIGNMENT, currentUser, Permission.MANAGE_MUTUAL_CHECK);
            case START_PCB_MUTUAL_REVIEW -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.PENDING_MUTUAL_ASSIGNMENT,
                    TaskStatus.MUTUAL_REVIEWING, currentUser, Permission.ASSIGN_PCB_MUTUAL_CHECK);
            case START_SCHEMATIC_MUTUAL_REVIEW -> requireTransition(reviewType == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT,
                    TaskStatus.MUTUAL_REVIEWING, currentUser, Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK);
            case PREPARE_SCHEMATIC_EXPERT_ASSIGNMENT -> requireTransition(reviewType == ReviewType.SCHEMATIC && current == TaskStatus.MUTUAL_REVIEWING,
                    TaskStatus.SCHEMATIC_PENDING_REVIEW, currentUser, Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT);
            case START_SCHEMATIC_EXPERT_REVIEW -> requireTransition(reviewType == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_PENDING_REVIEW,
                    TaskStatus.HARDWARE_REVIEWING, currentUser, Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT);
            case REQUEST_FINISH -> requireTransition(current == TaskStatus.MUTUAL_REVIEWING || current == TaskStatus.HARDWARE_REVIEWING
                            || current == TaskStatus.PCB_OPTIONAL_REVIEWING,
                    TaskStatus.PENDING_FINISH_CONFIRMATION, currentUser, reviewType == ReviewType.PCB ? Permission.MANAGE_MUTUAL_CHECK : Permission.FINISH_SCHEMATIC_TASK);
            case FINISH -> requireFinishTransition(reviewType, current, currentUser);
        };
    }

    private TaskStatus requireTransition(boolean allowedStatus, TaskStatus target, CurrentUser currentUser, Permission permission) {
        if (!allowedStatus) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许执行该流程动作");
        }
        if (!permissionPolicy.has(currentUser.roles(), permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无当前流程动作权限");
        }
        return target;
    }

    private TaskStatus requireFinishTransition(ReviewType type, TaskStatus current, CurrentUser currentUser) {
        Permission permission = type == ReviewType.PCB ? Permission.FINISH_PCB_TASK : Permission.FINISH_SCHEMATIC_TASK;
        return requireTransition(current == TaskStatus.PENDING_FINISH_CONFIRMATION, TaskStatus.FINISHED, currentUser, permission);
    }

    private boolean allActiveReviewersCompleted(long taskId) {
        List<ReviewerProgress> progress = reviewerMapper.findActiveByTaskId(taskId).stream().map(reviewer ->
                new ReviewerProgress(reviewer.getReviewerId(), ReviewerProcessStatus.valueOf(reviewer.getProcessStatus()),
                        opinionMapper.findStatusesByTaskAndRaisedBy(taskId, reviewer.getReviewerId()).stream().map(OpinionStatus::valueOf).toList()))
                .toList();
        return completionPolicy.isComplete(progress);
    }

    private boolean requiresAllReviewersCompleted(WorkflowAction action) {
        return action == WorkflowAction.PREPARE_PCB_MUTUAL_ASSIGNMENT
                || action == WorkflowAction.PREPARE_SCHEMATIC_EXPERT_ASSIGNMENT
                || action == WorkflowAction.REQUEST_FINISH;
    }

    private ReviewTaskRecord requireTask(long taskId) {
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        }
        return task;
    }

    private void appendHistory(long taskId, String fromStatus, String toStatus, WorkflowAction action, long operatorId, String comment) {
        TaskFlowRecord record = new TaskFlowRecord();
        record.setId(flowMapper.nextId());
        record.setTaskId(taskId);
        record.setFromStatus(fromStatus);
        record.setToStatus(toStatus);
        record.setAction(action.name());
        record.setOperatorId(operatorId);
        record.setComment(comment);
        flowMapper.insert(record);
    }

    public record WorkflowView(Long taskId, TaskStatus fromStatus, TaskStatus toStatus, long version) {
    }
}
