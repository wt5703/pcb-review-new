package com.leapmotor.pcbreview.review.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.application.TaskNodeAuthorizationService;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.review.domain.ReviewRole;
import com.leapmotor.pcbreview.review.domain.ReviewerProcessStatus;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerRecord;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 编排评审人员和互检人员的分配、改派与无意见提交，使用参与者记录表达多人并行进度，不推进任务整体状态。
 */
@Service
public class ReviewerAssignmentService {
    private final TaskReviewerMapper reviewerMapper;
    private final ReviewTaskMapper taskMapper;
    private final TaskNodeAuthorizationService taskNodeAuthorizationService;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public ReviewerAssignmentService(TaskReviewerMapper reviewerMapper, ReviewTaskMapper taskMapper,
                                     TaskNodeAuthorizationService taskNodeAuthorizationService, TaskAssignmentAccessMapper assignmentAccessMapper) {
        this.reviewerMapper = reviewerMapper;
        this.taskMapper = taskMapper;
        this.taskNodeAuthorizationService = taskNodeAuthorizationService;
        this.assignmentAccessMapper = assignmentAccessMapper;
    }

    @Transactional
    public List<ReviewerView> assign(long taskId, ReviewRole role, List<Long> reviewerIds, CurrentUser currentUser) {
        requireAssignmentPermission(role, currentUser);
        requireTaskExists(taskId);
        validateReviewerIds(reviewerIds);
        if (!reviewerMapper.findActiveByTaskAndRole(taskId, role.name()).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "该职责已有在途人员，请使用改派操作");
        }
        return createAssignments(taskId, role, reviewerIds, currentUser);
    }

    @Transactional
    public List<ReviewerView> reassign(long taskId, ReviewRole role, List<Long> reviewerIds, CurrentUser currentUser) {
        requireAssignmentPermission(role, currentUser);
        requireTaskExists(taskId);
        validateReviewerIds(reviewerIds);
        reviewerMapper.cancelActive(taskId, role.name());
        return createAssignments(taskId, role, reviewerIds, currentUser);
    }

    @Transactional
    public void submitNoOpinion(long taskId, CurrentUser currentUser) {
        taskNodeAuthorizationService.requireCurrentTaskProcessor(taskId, currentUser);
        if (reviewerMapper.submit(taskId, currentUser.id(), ReviewerProcessStatus.SUBMITTED.name(), true) != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前任务节点不需要该用户处理");
        }
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

    private void requireAssignmentPermission(ReviewRole role, CurrentUser currentUser) {
        if (!permissionPolicy.has(currentUser.roles(), role.assignmentPermission())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无对应人员分配权限");
        }
    }

    public record ReviewerView(Long id, ReviewRole role, Long reviewerId, ReviewerProcessStatus status, boolean noOpinion) {
        static ReviewerView from(TaskReviewerRecord record) {
            return new ReviewerView(record.getId(), ReviewRole.valueOf(record.getReviewRole()), record.getReviewerId(),
                    ReviewerProcessStatus.valueOf(record.getProcessStatus()), record.getNoOpinion());
        }
    }
}
