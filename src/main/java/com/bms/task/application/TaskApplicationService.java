package com.bms.task.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import com.bms.task.domain.ReviewTask;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.review.domain.ReviewRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
        requireTaskDesigner(command.designerId(), currentUser, "创建");
        long id = nextId();
        ReviewTask task = ReviewTask.draft(id, command.reviewType(), command.taskName(), command.projectName(),
                command.designerId(), command.designerName(), command.designName(), command.pcbType(), command.expectedCompletedDate(),
                command.expertLeaderId(), command.expertLeaderName(), command.reviewRoles(), command.reviewDescription());
        taskMapper.insert(toRecord(task, 0L));
        appendAudit(task.id(), "TASK_CREATED", currentUser.id(), "创建评审任务草稿");
        return TaskView.from(task, 0L);
    }

    @Transactional
    public TaskView submit(long taskId, List<Long> initialFileIds, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        ReviewTask task = restore(existing);
        requireTaskDesigner(task.designerId(), currentUser, "提交");
        initialFileIds.forEach(task::addInitialFile);
        task.submit();
        if (taskMapper.update(toRecord(task, existing.getVersion())) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "任务已被其他请求更新");
        }
        appendAudit(task.id(), "TASK_SUBMITTED", currentUser.id(), "提交评审任务，进入" + task.status().name());
        outboxEventMapper.insert(new OutboxEventRecord("TASK_SUBMITTED", "REVIEW_TASK", task.id(),
                "{\"taskId\":" + task.id() + ",\"reviewType\":\"" + task.reviewType().name() + "\"}", "PENDING"));
        return TaskView.from(task, existing.getVersion() + 1);
    }

    @Transactional
    public TaskView saveDraftFiles(long taskId, List<Long> initialFileIds, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        ReviewTask task = restore(existing);
        requireTaskDesigner(task.designerId(), currentUser, "保存草稿文件");
        initialFileIds.forEach(task::addInitialFile);
        if (taskMapper.update(toRecord(task, existing.getVersion())) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "任务已被其他请求更新");
        }
        appendAudit(task.id(), "TASK_DRAFT_FILES_SAVED", currentUser.id(), "保存草稿评审文件");
        return TaskView.from(task, existing.getVersion() + 1);
    }

    public TaskView get(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord record = requireRecord(taskId);
        ReviewTask task = restore(record);
        if (!canView(task, currentUser)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务");
        }
        return TaskView.from(task, record.getVersion());
    }

    public List<TaskView> list(CurrentUser currentUser) {
        return list(TaskQuery.defaultQuery(), currentUser).items();
    }

    public TaskPage list(TaskQuery query, CurrentUser currentUser) {
        TaskQuery validQuery = query == null ? TaskQuery.defaultQuery() : query.normalized();
        List<TaskView> visible = taskMapper.findAll().stream()
                .filter(record -> canView(restore(record), currentUser))
                .filter(record -> validQuery.matches(restore(record)))
                .sorted(Comparator.comparing(ReviewTaskRecord::getId))
                .map(record -> TaskView.from(restore(record), record.getVersion()))
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

    private void requireTaskDesigner(Long designerId, CurrentUser currentUser, String action) {
        if (designerId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "任务设计者不能为空");
        }
        if (!designerId.equals(currentUser.id()) && !currentUser.roles().contains(Role.HARDWARE_DEPARTMENT_MANAGER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者可以" + action + "该任务");
        }
    }

    public record CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, Long designerId, String designerName,
                                    String designName, String pcbType, LocalDate expectedCompletedDate, Long expertLeaderId,
                                    String expertLeaderName, List<ReviewRole> reviewRoles, String reviewDescription) {
        public CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, Long designerId, String designName, String pcbType) {
            this(reviewType, taskName, projectName, designerId, "设计者#" + designerId, designName, pcbType, LocalDate.now(),
                    designerId, "专家/组长#" + designerId, List.of(ReviewRole.PCB_EXPERT), null);
        }
    }

    public record TaskView(Long id, ReviewType reviewType, String taskName, String projectName, Long designerId, String designerName,
                           String designName, String pcbType, LocalDate expectedCompletedDate, Long expertLeaderId, String expertLeaderName,
                           List<ReviewRole> reviewRoles, String reviewDescription, String status, long version) {
        static TaskView from(ReviewTask task, long version) {
            return new TaskView(task.id(), task.reviewType(), task.taskName(), task.projectName(), task.designerId(), task.designerName(),
                    task.designName(), task.pcbType(), task.expectedCompletedDate(), task.expertLeaderId(), task.expertLeaderName(),
                    task.reviewRoles(), task.reviewDescription(), task.status().name(), version);
        }
    }

    public record TaskPage(long total, int pageNo, int pageSize, List<TaskView> items) {
        public TaskPage {
            items = List.copyOf(items);
        }
    }

    public record TaskQuery(String taskName, String projectName, String designerName, ReviewType reviewType, TaskStatus status,
                            Integer pageNo, Integer pageSize) {
        public TaskQuery(String taskName, String projectName, String designerName, ReviewType reviewType, int pageNo, int pageSize) {
            this(taskName, projectName, designerName, reviewType, null, pageNo, pageSize);
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
            return new TaskQuery(taskName, projectName, designerName, reviewType, status, normalizedPageNo, normalizedPageSize);
        }

        boolean matches(ReviewTask task) {
            return contains(task.taskName(), taskName)
                    && contains(task.projectName(), projectName)
                    && contains(task.designerName(), designerName)
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
        record.setProjectName(task.projectName()); record.setDesignerId(task.designerId()); record.setDesignerName(task.designerName()); record.setDesignName(task.designName());
        record.setPcbType(task.pcbType()); record.setExpectedCompletedDate(task.expectedCompletedDate()); record.setExpertLeaderId(task.expertLeaderId());
        record.setExpertLeaderName(task.expertLeaderName()); record.setReviewRoles(task.reviewRoles().stream().map(Enum::name).collect(Collectors.joining(",")));
        record.setReviewDescription(task.reviewDescription()); record.setStatus(task.status().name());
        record.setInitialFileIds(task.initialFileIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setVersion(version); return record;
    }

    private ReviewTask restore(ReviewTaskRecord record) {
        List<Long> fileIds = record.getInitialFileIds() == null || record.getInitialFileIds().isBlank() ? List.of() : java.util.Arrays.stream(record.getInitialFileIds().split(",")).map(Long::valueOf).toList();
        List<ReviewRole> roles = record.getReviewRoles() == null || record.getReviewRoles().isBlank() ? List.of(ReviewRole.PCB_EXPERT)
                : java.util.Arrays.stream(record.getReviewRoles().split(",")).map(ReviewRole::valueOf).toList();
        return ReviewTask.restore(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getTaskName(), record.getProjectName(), record.getDesignerId(),
                record.getDesignerName() == null || record.getDesignerName().isBlank() ? "设计者#" + record.getDesignerId() : record.getDesignerName(),
                record.getDesignName(), record.getPcbType(), record.getExpectedCompletedDate() == null ? LocalDate.now() : record.getExpectedCompletedDate(),
                record.getExpertLeaderId() == null ? record.getDesignerId() : record.getExpertLeaderId(),
                record.getExpertLeaderName() == null || record.getExpertLeaderName().isBlank() ? "专家/组长#" + record.getDesignerId() : record.getExpertLeaderName(),
                roles, record.getReviewDescription(), TaskStatus.valueOf(record.getStatus()), fileIds);
    }
}
