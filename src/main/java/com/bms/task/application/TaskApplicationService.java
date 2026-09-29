package com.bms.task.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.infrastructure.NotificationSendRecord;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
import com.bms.notification.application.ReviewMailNotificationApplicationService;
import com.bms.task.domain.ReviewTask;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.domain.WorkflowAction;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.domain.TaskReviewerAssignmentCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper;
    private final TaskFlowMapper flowMapper;
    private final NotificationSendRecordMapper notificationSendRecordMapper;
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService;
    private final ReviewMailNotificationApplicationService reviewMailNotificationApplicationService;

    @Autowired
    public TaskApplicationService(ReviewTaskMapper taskMapper,
                                  TaskAssignmentAccessMapper taskAssignmentAccessMapper, TaskFlowMapper flowMapper,
                                  NotificationSendRecordMapper notificationSendRecordMapper,
                                  ReviewerWhitelistApplicationService reviewerWhitelistApplicationService,
                                  ReviewMailNotificationApplicationService reviewMailNotificationApplicationService) {
        this.taskMapper = taskMapper;
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
        this.flowMapper = flowMapper;
        this.notificationSendRecordMapper = notificationSendRecordMapper;
        this.reviewerWhitelistApplicationService = reviewerWhitelistApplicationService;
        this.reviewMailNotificationApplicationService = reviewMailNotificationApplicationService;
    }

    @Transactional
    public TaskView create(CreateTaskCommand command, CurrentUser currentUser) {
        require(currentUser, Permission.CREATE_TASK);
        requireTaskDesigner(command.designerId(), currentUser, "创建");
        validateReviewerAssignments(command);
        long id = nextId();
        ReviewTask task = ReviewTask.draft(id, command.reviewType(), command.taskName(), command.projectName(),
                command.designerId(), command.designerName(), command.designName(), command.pcbType(), command.expectedCompletedDate(),
                command.expertLeaderId(), command.expertLeaderName(), command.reviewRoles(), command.reviewerAssignments(), command.reviewDescription());
        taskMapper.insert(toRecord(task));
        return TaskView.from(task);
    }

    @Transactional
    public TaskView submit(long taskId, List<Long> initialFileIds, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        ReviewTask task = restore(existing);
        requireTaskDesigner(task.designerId(), currentUser, "提交");
        initialFileIds.forEach(task::addInitialFile);
        task.submit();
        if (taskMapper.update(toRecord(task)) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        appendFlow(task.id(), WorkflowAction.CREATE, currentUser.id(), "提交评审任务");
        reviewMailNotificationApplicationService.enqueueTaskCreated(task);
        return TaskView.from(task);
    }

    @Transactional
    public TaskView updateDraft(long taskId, CreateTaskCommand command, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        if (TaskStatus.DRAFT != TaskStatus.valueOf(existing.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已提交或已产生评审意见的任务不允许编辑");
        }
        requireTaskDesigner(existing.getDesignerId(), currentUser, "编辑");
        if (!existing.getDesignerId().equals(command.designerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "编辑任务时不允许变更设计者");
        }
        validateReviewerAssignments(command);
        ReviewTask updated = ReviewTask.draft(taskId, command.reviewType(), command.taskName(), command.projectName(),
                command.designerId(), command.designerName(), command.designName(), command.pcbType(), command.expectedCompletedDate(),
                command.expertLeaderId(), command.expertLeaderName(), command.reviewRoles(), command.reviewerAssignments(), command.reviewDescription());
        existing.setReviewType(updated.reviewType().name()); existing.setTaskName(updated.taskName()); existing.setProjectName(updated.projectName());
        existing.setDesignerName(updated.designerName()); existing.setDesignName(updated.designName()); existing.setPcbType(updated.pcbType());
        existing.setExpectedCompletedDate(updated.expectedCompletedDate()); existing.setExpertLeaderId(updated.expertLeaderId());
        existing.setExpertLeaderName(updated.expertLeaderName()); existing.setReviewRoles(updated.reviewRoles().stream().map(Enum::name).collect(Collectors.joining(",")));
        existing.setReviewerAssignments(TaskReviewerAssignmentCodec.encode(updated.reviewerAssignments()));
        existing.setAssignedReviewerIds(TaskReviewerAssignmentCodec.flattenReviewerIds(updated.reviewerAssignments()));
        existing.setReviewDescription(updated.reviewDescription());
        if (taskMapper.updateDraft(existing) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "任务已提交，不允许编辑");
        }
        return TaskView.from(updated);
    }

    @Transactional
    public TaskView saveDraftFiles(long taskId, List<Long> initialFileIds, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        ReviewTask task = restore(existing);
        requireTaskDesigner(task.designerId(), currentUser, "保存草稿文件");
        initialFileIds.forEach(task::addInitialFile);
        if (taskMapper.update(toRecord(task)) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        return TaskView.from(task);
    }

    public TaskView get(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord record = requireRecord(taskId);
        ReviewTask task = restore(record);
        if (!canView(task, currentUser)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务");
        }
        List<NotificationRecordView> notificationRecords = notificationSendRecordMapper.findByTaskId(taskId).stream()
                .map(NotificationRecordView::from)
                .toList();
        return TaskView.from(task, notificationRecords);
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
                .map(record -> TaskView.from(restore(record)))
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

    private void appendFlow(long taskId, WorkflowAction action, long operateId, String comment) {
        TaskFlowRecord record = new TaskFlowRecord();
        record.setId(flowMapper.nextId());
        record.setTaskId(taskId);
        record.setAction(action.name());
        record.setActionName(action.actionName());
        record.setOperateId(operateId);
        record.setComment(comment);
        flowMapper.insert(record);
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
        if (!permissionPolicy.isAdministrator(currentUser.roles())
                && (!currentUser.roles().contains(Role.DESIGNER) || !designerId.equals(currentUser.id()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者可以" + action + "该任务");
        }
    }

    /**
     * 创建时的角色与人员就是首轮专家评审的依据：每个勾选角色至少指定一位对应白名单专家。
     * PCB 允许五类评审，原理图仅允许硬件、EMC、PCB 三类评审。
     */
    private void validateReviewerAssignments(CreateTaskCommand command) {
        Set<ReviewRole> allowedRoles = command.reviewType() == ReviewType.PCB
                ? EnumSet.of(ReviewRole.HARDWARE_EXPERT, ReviewRole.EMC_EXPERT, ReviewRole.PCB_EXPERT,
                ReviewRole.PROCESS_EXPERT, ReviewRole.STRUCTURE_EXPERT)
                : EnumSet.of(ReviewRole.HARDWARE_EXPERT, ReviewRole.EMC_EXPERT, ReviewRole.PCB_EXPERT);
        List<ReviewRole> roles = command.reviewRoles() == null ? List.of() : command.reviewRoles();
        if (roles.isEmpty() || roles.stream().anyMatch(role -> !allowedRoles.contains(role))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, command.reviewType() == ReviewType.PCB
                    ? "PCB 任务仅可选择硬件、EMC、PCB、工艺、结构评审角色"
                    : "原理图任务仅可选择硬件、EMC、PCB 评审角色");
        }
        if (roles.stream().distinct().count() != roles.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评审角色不能重复选择");
        }
        List<TaskReviewerAssignment> assignments = command.reviewerAssignments() == null ? List.of() : command.reviewerAssignments();
        // 兼容存量接口调用：旧客户端未传该字段时仍以历史专家/组长逻辑处理；新页面始终传完整映射。
        if (assignments.isEmpty()) {
            return;
        }
        Map<ReviewRole, List<Long>> reviewersByRole = assignments.stream().collect(Collectors.toMap(
                TaskReviewerAssignment::reviewRole, TaskReviewerAssignment::reviewerIds,
                (left, right) -> { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "同一评审角色只能选择一组专家"); }));
        if (!reviewersByRole.keySet().equals(Set.copyOf(roles)) || reviewersByRole.values().stream().anyMatch(List::isEmpty)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "每个已勾选的评审角色都必须选择至少一名专家");
        }
        for (ReviewRole role : roles) {
            Set<Long> whitelistUserIds = reviewerWhitelistApplicationService.listAssignableUsers(
                            List.of(ReviewerWhitelistRole.fromReviewRole(role))).stream()
                    .map(ReviewerWhitelistApplicationService.AssignableReviewerView::userId)
                    .collect(Collectors.toSet());
            if (!whitelistUserIds.containsAll(reviewersByRole.get(role))) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "所选专家不属于“" + role.name() + "”角色白名单");
            }
        }
    }

    public record CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, Long designerId, String designerName,
                                    String designName, String pcbType, LocalDate expectedCompletedDate, Long expertLeaderId,
                                    String expertLeaderName, List<ReviewRole> reviewRoles,
                                    List<TaskReviewerAssignment> reviewerAssignments, String reviewDescription) {
        public CreateTaskCommand {
            reviewRoles = reviewRoles == null ? List.of() : List.copyOf(reviewRoles);
            reviewerAssignments = reviewerAssignments == null ? List.of() : List.copyOf(reviewerAssignments);
        }
        public CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, Long designerId, String designerName,
                                 String designName, String pcbType, LocalDate expectedCompletedDate, Long expertLeaderId,
                                 String expertLeaderName, List<ReviewRole> reviewRoles, String reviewDescription) {
            this(reviewType, taskName, projectName, designerId, designerName, designName, pcbType, expectedCompletedDate,
                    expertLeaderId, expertLeaderName, reviewRoles, List.of(), reviewDescription);
        }
        public CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, Long designerId, String designName, String pcbType) {
            this(reviewType, taskName, projectName, designerId, "设计者#" + designerId, designName, pcbType, LocalDate.now(),
                    designerId, "专家/组长#" + designerId, List.of(ReviewRole.PCB_EXPERT), List.of(), null);
        }
    }

    public record TaskView(Long id, ReviewType reviewType, String taskName, String projectName, Long designerId, String designerName,
                           String designName, String pcbType, LocalDate expectedCompletedDate, Long expertLeaderId, String expertLeaderName,
                           List<ReviewRole> reviewRoles, List<TaskReviewerAssignment> reviewerAssignments, String reviewDescription, String status,
                           List<NotificationRecordView> notificationRecords) {
        static TaskView from(ReviewTask task) {
            return new TaskView(task.id(), task.reviewType(), task.taskName(), task.projectName(), task.designerId(), task.designerName(),
                    task.designName(), task.pcbType(), task.expectedCompletedDate(), task.expertLeaderId(), task.expertLeaderName(),
                    task.reviewRoles(), task.reviewerAssignments(), task.reviewDescription(), task.status().name(), List.of());
        }

        static TaskView from(ReviewTask task, List<NotificationRecordView> notificationRecords) {
            TaskView taskView = from(task);
            return new TaskView(taskView.id(), taskView.reviewType(), taskView.taskName(), taskView.projectName(),
                    taskView.designerId(), taskView.designerName(), taskView.designName(), taskView.pcbType(),
                    taskView.expectedCompletedDate(), taskView.expertLeaderId(), taskView.expertLeaderName(),
                    taskView.reviewRoles(), taskView.reviewerAssignments(), taskView.reviewDescription(), taskView.status(), List.copyOf(notificationRecords));
        }
    }

    public record NotificationRecordView(String eventType, String recipient, String templateCode,
                                         String deliveryStatus, String failureReason, java.time.LocalDateTime attemptedAt) {
        static NotificationRecordView from(NotificationSendRecord record) {
            return new NotificationRecordView(record.eventType(), record.recipient(), record.templateCode(),
                    record.deliveryStatus(), record.failureReason(), record.attemptedAt());
        }
    }

    public record TaskPage(long total, int pageNo, int pageSize, List<TaskView> items) {
        public TaskPage {
            items = List.copyOf(items);
        }
    }

    public record TaskQuery(String taskName, String projectName, String designerName, ReviewType reviewType, TaskStatus status,
                            Integer pageNo, Integer pageSize, Long taskId) {
        public TaskQuery(String taskName, String projectName, String designerName, ReviewType reviewType, int pageNo, int pageSize) {
            this(taskName, projectName, designerName, reviewType, null, pageNo, pageSize, null);
        }

        static TaskQuery defaultQuery() {
            return new TaskQuery(null, null, null, null, null, 1, 20, null);
        }

        TaskQuery normalized() {
            int normalizedPageNo = pageNo == null ? 1 : pageNo;
            int normalizedPageSize = pageSize == null ? 20 : pageSize;
            if (normalizedPageNo < 1 || normalizedPageSize < 1 || normalizedPageSize > 100) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数不合法");
            }
            return new TaskQuery(taskName, projectName, designerName, reviewType, status, normalizedPageNo, normalizedPageSize, taskId);
        }

        boolean matches(ReviewTask task) {
            return contains(task.taskName(), taskName)
                    && contains(task.projectName(), projectName)
                    && contains(task.designerName(), designerName)
                    && (reviewType == null || reviewType == task.reviewType())
                    && (status == null || status == task.status())
                    && (taskId == null || taskId == task.id());
        }

        private static boolean contains(String value, String condition) {
            return condition == null || condition.isBlank() || value.toLowerCase().contains(condition.trim().toLowerCase());
        }
    }

    private ReviewTaskRecord toRecord(ReviewTask task) {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(task.id()); record.setReviewType(task.reviewType().name()); record.setTaskName(task.taskName());
        record.setProjectName(task.projectName()); record.setDesignerId(task.designerId()); record.setDesignerName(task.designerName()); record.setDesignName(task.designName());
        record.setPcbType(task.pcbType()); record.setExpectedCompletedDate(task.expectedCompletedDate()); record.setExpertLeaderId(task.expertLeaderId());
        record.setExpertLeaderName(task.expertLeaderName()); record.setReviewRoles(task.reviewRoles().stream().map(Enum::name).collect(Collectors.joining(",")));
        record.setReviewerAssignments(TaskReviewerAssignmentCodec.encode(task.reviewerAssignments()));
        record.setAssignedReviewerIds(TaskReviewerAssignmentCodec.flattenReviewerIds(task.reviewerAssignments()));
        record.setReviewDescription(task.reviewDescription()); record.setStatus(task.status().name());
        record.setInitialFileIds(task.initialFileIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setVersion(0L); return record;
    }

    private ReviewTask restore(ReviewTaskRecord record) {
        List<Long> fileIds = record.getInitialFileIds() == null || record.getInitialFileIds().isBlank() ? List.of() : java.util.Arrays.stream(record.getInitialFileIds().split(",")).map(Long::valueOf).toList();
        List<ReviewRole> roles = record.getReviewRoles() == null || record.getReviewRoles().isBlank() ? List.of(ReviewRole.PCB_EXPERT)
                : java.util.Arrays.stream(record.getReviewRoles().split(",")).map(ReviewRole::valueOf).toList();
        List<TaskReviewerAssignment> reviewerAssignments = TaskReviewerAssignmentCodec.decode(record.getReviewerAssignments());
        return ReviewTask.restore(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getTaskName(), record.getProjectName(), record.getDesignerId(),
                record.getDesignerName() == null || record.getDesignerName().isBlank() ? "设计者#" + record.getDesignerId() : record.getDesignerName(),
                record.getDesignName(), record.getPcbType(), record.getExpectedCompletedDate() == null ? LocalDate.now() : record.getExpectedCompletedDate(),
                record.getExpertLeaderId() == null ? record.getDesignerId() : record.getExpertLeaderId(),
                record.getExpertLeaderName() == null || record.getExpertLeaderName().isBlank() ? "专家/组长#" + record.getDesignerId() : record.getExpertLeaderName(),
                roles, reviewerAssignments, record.getReviewDescription(), TaskStatus.valueOf(record.getStatus()), fileIds);
    }
}
