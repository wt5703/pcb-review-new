package com.leapmotor.pcbreview.task.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditMapper;
import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditRecord;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventMapper;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventRecord;
import com.leapmotor.pcbreview.task.domain.ReviewTask;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 编排评审任务创建、提交、查询和可见性校验，协调当前用户上下文、任务领域模型与持久化接口，不承载具体 HTTP 协议细节。
 */


@Service
public class TaskApplicationService {
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();
    private final ReviewTaskMapper taskMapper;
    private final OperationAuditMapper auditMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper;

    public TaskApplicationService(ReviewTaskMapper taskMapper, OperationAuditMapper auditMapper, OutboxEventMapper outboxEventMapper,
                                  TaskAssignmentAccessMapper taskAssignmentAccessMapper) {
        this.taskMapper = taskMapper;
        this.auditMapper = auditMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
    }

    @Transactional
    public TaskView create(CreateTaskCommand command, CurrentUser currentUser) {
        require(currentUser, Permission.CREATE_TASK);
        long id = nextId();
        ReviewTask task = ReviewTask.draft(id, command.reviewType(), command.taskName(), command.projectName(),
                command.designerId(), command.designName(), command.pcbType());
        taskMapper.insert(toRecord(task, 0L));
        appendAudit(task.id(), "TASK_CREATED", currentUser.id(), "创建评审任务草稿");
        return TaskView.from(task);
    }

    @Transactional
    public TaskView submit(long taskId, List<Long> initialFileIds, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        ReviewTask task = restore(existing);
        if (!task.designerId().equals(currentUser.id()) && !permissionPolicy.has(currentUser.roles(), Permission.CREATE_TASK)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权提交该任务");
        }
        initialFileIds.forEach(task::addInitialFile);
        task.submit();
        if (taskMapper.update(toRecord(task, existing.getVersion())) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "任务已被其他请求更新");
        }
        appendAudit(task.id(), "TASK_SUBMITTED", currentUser.id(), "提交评审任务，进入" + task.status().name());
        outboxEventMapper.insert(new OutboxEventRecord("TASK_SUBMITTED", "REVIEW_TASK", task.id(),
                "{\"taskId\":" + task.id() + ",\"reviewType\":\"" + task.reviewType().name() + "\"}", "PENDING"));
        return TaskView.from(task);
    }

    public TaskView get(long taskId, CurrentUser currentUser) {
        ReviewTask task = restore(requireRecord(taskId));
        if (!canView(task, currentUser)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务");
        }
        return TaskView.from(task);
    }

    public List<TaskView> list(CurrentUser currentUser) {
        return list(TaskQuery.defaultQuery(), currentUser).items();
    }

    public TaskPage list(TaskQuery query, CurrentUser currentUser) {
        TaskQuery validQuery = query == null ? TaskQuery.defaultQuery() : query.normalized();
        List<TaskView> visible = taskMapper.findAll().stream()
                .map(this::restore)
                .filter(task -> canView(task, currentUser))
                .filter(task -> validQuery.matches(task))
                .sorted(Comparator.comparing(ReviewTask::id))
                .map(TaskView::from)
                .toList();
        int fromIndex = Math.min((validQuery.pageNo() - 1) * validQuery.pageSize(), visible.size());
        int toIndex = Math.min(fromIndex + validQuery.pageSize(), visible.size());
        return new TaskPage(visible.size(), validQuery.pageNo(), validQuery.pageSize(), visible.subList(fromIndex, toIndex));
    }

    private synchronized long nextId() {
        return taskMapper.nextId();
    }

    private ReviewTaskRecord requireRecord(long taskId) {
        ReviewTaskRecord record = taskMapper.findById(taskId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        return record;
    }

    private void appendAudit(long taskId, String action, long operatorId, String detail) {
        auditMapper.insert(new OperationAuditRecord("REVIEW_TASK", taskId, action, operatorId, detail));
    }

    private boolean canView(ReviewTask task, CurrentUser currentUser) {
        if (permissionPolicy.canViewAllTasks(currentUser.roles())) {
            return true;
        }
        return permissionPolicy.canViewCurrentTask(currentUser.roles(),
                taskAssignmentAccessMapper.isAssignedToTask(task.id(), currentUser.id()));
    }

    private void require(CurrentUser currentUser, Permission permission) {
        if (!permissionPolicy.has(currentUser.roles(), permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无对应功能权限");
        }
    }

    public record CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, Long designerId,
                                    String designName, String pcbType) {
    }

    public record TaskView(Long id, ReviewType reviewType, String taskName, String projectName, Long designerId,
                           String designName, String pcbType, String status) {
        static TaskView from(ReviewTask task) {
            return new TaskView(task.id(), task.reviewType(), task.taskName(), task.projectName(), task.designerId(),
                    task.designName(), task.pcbType(), task.status().name());
        }
    }

    public record TaskPage(long total, int pageNo, int pageSize, List<TaskView> items) {
        public TaskPage {
            items = List.copyOf(items);
        }
    }

    public record TaskQuery(String taskName, String projectName, Long designerId, ReviewType reviewType, TaskStatus status,
                            Integer pageNo, Integer pageSize) {
        public TaskQuery(String taskName, String projectName, Long designerId, ReviewType reviewType, int pageNo, int pageSize) {
            this(taskName, projectName, designerId, reviewType, null, pageNo, pageSize);
        }

        static TaskQuery defaultQuery() {
            return new TaskQuery(null, null, null, null, null, 1, 20);
        }

        TaskQuery normalized() {
            int normalizedPageNo = pageNo == null ? 1 : pageNo;
            int normalizedPageSize = pageSize == null ? 20 : pageSize;
            if (normalizedPageNo < 1 || normalizedPageSize < 1 || normalizedPageSize > 100) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数不合法");
            }
            return new TaskQuery(taskName, projectName, designerId, reviewType, status, normalizedPageNo, normalizedPageSize);
        }

        boolean matches(ReviewTask task) {
            return contains(task.taskName(), taskName)
                    && contains(task.projectName(), projectName)
                    && (designerId == null || designerId.equals(task.designerId()))
                    && (reviewType == null || reviewType == task.reviewType())
                    && (status == null || status == task.status());
        }

        private static boolean contains(String value, String condition) {
            return condition == null || condition.isBlank() || value.toLowerCase().contains(condition.trim().toLowerCase());
        }
    }

    private ReviewTaskRecord toRecord(ReviewTask task, long version) {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(task.id()); record.setReviewType(task.reviewType().name()); record.setTaskName(task.taskName());
        record.setProjectName(task.projectName()); record.setDesignerId(task.designerId()); record.setDesignName(task.designName());
        record.setPcbType(task.pcbType()); record.setStatus(task.status().name());
        record.setInitialFileIds(task.initialFileIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setVersion(version); return record;
    }

    private ReviewTask restore(ReviewTaskRecord record) {
        List<Long> fileIds = record.getInitialFileIds() == null || record.getInitialFileIds().isBlank() ? List.of() : java.util.Arrays.stream(record.getInitialFileIds().split(",")).map(Long::valueOf).toList();
        return ReviewTask.restore(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getTaskName(), record.getProjectName(), record.getDesignerId(), record.getDesignName(), record.getPcbType(), TaskStatus.valueOf(record.getStatus()), fileIds);
    }
}
