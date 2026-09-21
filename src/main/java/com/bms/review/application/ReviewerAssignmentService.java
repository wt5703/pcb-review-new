package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.TaskNodeAuthorizationService;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerProcessStatus;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 为流程域提供评审人员首次分配、无意见提交和在途人员查询能力；任务整体状态仅由 WorkflowApplicationService 统一推进。
 */
@Service
public class ReviewerAssignmentService {
    private final TaskReviewerMapper reviewerMapper;
    private final ReviewTaskMapper taskMapper;
    private final TaskNodeAuthorizationService taskNodeAuthorizationService;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final OutboxEventPublisher outboxEventPublisher;
    private final OperationAuditMapper auditMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public ReviewerAssignmentService(TaskReviewerMapper reviewerMapper, ReviewTaskMapper taskMapper,
                                     TaskNodeAuthorizationService taskNodeAuthorizationService, TaskAssignmentAccessMapper assignmentAccessMapper,
                                     OutboxEventPublisher outboxEventPublisher, OperationAuditMapper auditMapper) {
        this.reviewerMapper = reviewerMapper;
        this.taskMapper = taskMapper;
        this.taskNodeAuthorizationService = taskNodeAuthorizationService;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.outboxEventPublisher = outboxEventPublisher;
        this.auditMapper = auditMapper;
    }

    @Transactional
    public List<ReviewerView> assign(long taskId, ReviewRole role, List<Long> reviewerIds, CurrentUser currentUser) {
        requireAssignmentAuthorized(taskId, role, currentUser, false);
        validateReviewerIds(reviewerIds);
        if (!reviewerMapper.findActiveByTaskAndRole(taskId, role.name()).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "该职责已有在途人员，不允许重复分配");
        }
        List<ReviewerView> views = createAssignments(taskId, role, reviewerIds, currentUser);
        appendAudit(taskId, "REVIEWERS_ASSIGNED", currentUser.id(), role.name());
        outboxEventPublisher.publishTaskEvent("REVIEWERS_ASSIGNED", taskId, currentUser.id());
        return views;
    }

    @Transactional
    public List<ReviewerView> reassign(long taskId, ReviewRole role, List<Long> reviewerIds, CurrentUser currentUser) {
        requireAssignmentAuthorized(taskId, role, currentUser, true);
        validateReviewerIds(reviewerIds);
        reviewerMapper.cancelActive(taskId, role.name());
        List<ReviewerView> views = createAssignments(taskId, role, reviewerIds, currentUser);
        appendAudit(taskId, "REVIEWERS_REASSIGNED", currentUser.id(), role.name());
        outboxEventPublisher.publishTaskEvent("REVIEWERS_REASSIGNED", taskId, currentUser.id());
        return views;
    }

    @Transactional
    public void submitNoOpinion(long taskId, CurrentUser currentUser) {
        taskNodeAuthorizationService.requireCurrentTaskProcessor(taskId, currentUser);
        if (reviewerMapper.submit(taskId, currentUser.id(), ReviewerProcessStatus.SUBMITTED.name(), true) != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前任务节点不需要该用户处理");
        }
        appendAudit(taskId, "REVIEWER_NO_OPINION_SUBMITTED", currentUser.id(), "提交无意见");
    }

    public List<ReviewerView> listActive(long taskId, ReviewRole role, CurrentUser currentUser) {
        requireTaskExists(taskId);
        if (!permissionPolicy.canViewAllTasks(currentUser.roles())
                && !assignmentAccessMapper.isAssignedToTask(taskId, currentUser.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务的评审人员");
        }
        return reviewerMapper.findActiveByTaskAndRole(taskId, role.name()).stream().map(ReviewerView::from).toList();
    }

    public boolean areAllReviewersSubmitted(long taskId, ReviewRole role) {
        List<TaskReviewerRecord> reviewers = reviewerMapper.findActiveByTaskAndRole(taskId, role.name());
        return !reviewers.isEmpty() && reviewers.stream().allMatch(record -> {
            ReviewerProcessStatus status = ReviewerProcessStatus.valueOf(record.getProcessStatus());
            return status == ReviewerProcessStatus.SUBMITTED || status == ReviewerProcessStatus.COMPLETED;
        });
    }

    private synchronized long nextId() {
        return reviewerMapper.nextId();
    }

    private List<ReviewerView> createAssignments(long taskId, ReviewRole role, List<Long> reviewerIds, CurrentUser currentUser) {
        return reviewerIds.stream().distinct().map(reviewerId -> {
            TaskReviewerRecord record = new TaskReviewerRecord();
            record.setId(nextId());
            record.setTaskId(taskId);
            record.setReviewRole(role.name());
            record.setReviewerId(reviewerId);
            record.setProcessStatus(ReviewerProcessStatus.PENDING.name());
            record.setAssignedBy(currentUser.id());
            record.setNoOpinion(false);
            record.setVersion(0L);
            reviewerMapper.insert(record);
            return ReviewerView.from(record);
        }).toList();
    }

    private void validateReviewerIds(List<Long> reviewerIds) {
        if (reviewerIds == null || reviewerIds.isEmpty() || reviewerIds.stream().anyMatch(id -> id == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "至少需要指定一名评审人员");
        }
    }

    private void requireTaskExists(long taskId) {
        if (taskMapper.findById(taskId) == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        }
    }

    private void requireAssignmentAuthorized(long taskId, ReviewRole role, CurrentUser currentUser, boolean reassign) {
        requireAssignmentPermission(role, currentUser);
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        }
        if (TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许分配或改派人员");
        }
        if (role == ReviewRole.PCB_EXPERT && !isTaskDesignerOrManager(task, currentUser)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者可以分配 PCB 评审专家");
        }
        if (!isRoleAvailableAtStatus(task, role, reassign)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务节点不允许分配该职责人员");
        }
    }

    private boolean isTaskDesignerOrManager(ReviewTaskRecord task, CurrentUser currentUser) {
        return task.getDesignerId().equals(currentUser.id())
                || currentUser.roles().contains(Role.HARDWARE_DEPARTMENT_MANAGER);
    }

    private boolean isRoleAvailableAtStatus(ReviewTaskRecord task, ReviewRole role, boolean reassign) {
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        ReviewType type = ReviewType.valueOf(task.getReviewType());
        return switch (role) {
            case HARDWARE_EXPERT, EMC_EXPERT, PCB_EXPERT -> type == ReviewType.PCB && (status == TaskStatus.PCB_PENDING_REVIEW
                    || reassign && status == TaskStatus.PCB_EXPERT_REVIEWING);
            case PROCESS_EXPERT, STRUCTURE_EXPERT -> type == ReviewType.PCB && (status == TaskStatus.PCB_EXPERT_REVIEWING
                    || reassign && status == TaskStatus.PCB_OPTIONAL_REVIEWING);
            case PCB_MUTUAL_CHECK -> type == ReviewType.PCB && (status == TaskStatus.PENDING_MUTUAL_ASSIGNMENT
                    || reassign && status == TaskStatus.MUTUAL_REVIEWING);
            case SCHEMATIC_MUTUAL_CHECK -> type == ReviewType.SCHEMATIC && (status == TaskStatus.SCHEMATIC_PENDING_LEADER_ASSIGNMENT
                    || status == TaskStatus.SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT
                    || reassign && status == TaskStatus.MUTUAL_REVIEWING);
            case SCHEMATIC_HARDWARE_EXPERT, SCHEMATIC_OTHER_EXPERT -> type == ReviewType.SCHEMATIC
                    && (status == TaskStatus.SCHEMATIC_PENDING_REVIEW || reassign && status == TaskStatus.HARDWARE_REVIEWING);
            case SCHEMATIC_LEADER -> false;
        };
    }

    private void requireAssignmentPermission(ReviewRole role, CurrentUser currentUser) {
        if (!permissionPolicy.has(currentUser.roles(), role.assignmentPermission())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无对应人员分配权限");
        }
    }

    private void appendAudit(long taskId, String action, long operatorId, String detail) {
        auditMapper.insert(new OperationAuditRecord("REVIEW_TASK", taskId, action, operatorId, detail));
    }

    public record ReviewerView(Long id, ReviewRole role, Long reviewerId, ReviewerProcessStatus status, boolean noOpinion) {
        static ReviewerView from(TaskReviewerRecord record) {
            return new ReviewerView(record.getId(), ReviewRole.valueOf(record.getReviewRole()), record.getReviewerId(),
                    ReviewerProcessStatus.valueOf(record.getProcessStatus()), record.getNoOpinion());
        }
    }
}
