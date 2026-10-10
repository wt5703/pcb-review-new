package com.bms.task.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.application.FileApplicationService;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
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
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService;
    private final ReviewMailNotificationApplicationService reviewMailNotificationApplicationService;
    private final UserCenterUserProfileClient userProfileClient;
    private final FileApplicationService fileApplicationService;

    @Autowired
    public TaskApplicationService(ReviewTaskMapper taskMapper,
                                  TaskAssignmentAccessMapper taskAssignmentAccessMapper, TaskFlowMapper flowMapper,
                                  ReviewerWhitelistApplicationService reviewerWhitelistApplicationService,
                                  ReviewMailNotificationApplicationService reviewMailNotificationApplicationService,
                                  UserCenterUserProfileClient userProfileClient,
                                  FileApplicationService fileApplicationService) {
        this.taskMapper = taskMapper;
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
        this.flowMapper = flowMapper;
        this.reviewerWhitelistApplicationService = reviewerWhitelistApplicationService;
        this.reviewMailNotificationApplicationService = reviewMailNotificationApplicationService;
        this.userProfileClient = userProfileClient;
        this.fileApplicationService = fileApplicationService;
    }

    /**
     * 保存任务表单并关联前端预上传的初始文件。文件只以 UUID 传入，实际绑定与任务保存保持同一事务。
     */
    @Transactional
    public TaskView createWithInitialFiles(CreateTaskCommand command, List<String> fileIds,
                                           boolean submit, CurrentUser currentUser) {
        TaskView draft = create(command, currentUser);
        List<Long> taskFileIds = bindInitialFiles(draft.id(), fileIds, currentUser);
        return submit ? submit(draft.id(), taskFileIds, currentUser)
                : taskFileIds.isEmpty() ? draft : saveDraftFiles(draft.id(), taskFileIds, currentUser);
    }

    /**
     * 编辑草稿并关联本次传入的初始文件。已绑定到当前任务的 UUID 由文件服务幂等处理，不会重复关联。
     */
    @Transactional
    public TaskView updateDraftWithInitialFiles(long taskId, CreateTaskCommand command, List<String> fileIds,
                                                boolean submit, CurrentUser currentUser) {
        TaskView draft = updateDraft(taskId, command, currentUser);
        List<Long> taskFileIds = bindInitialFiles(taskId, fileIds, currentUser);
        // 提交时直接将既有草稿文件与本次关联的文件统一写入任务，避免重复追加同一批文件 ID。
        return submit ? submit(taskId, taskFileIds, currentUser)
                : taskFileIds.isEmpty() ? draft : saveDraftFiles(taskId, taskFileIds, currentUser);
    }

    @Transactional
    public TaskView create(CreateTaskCommand command, CurrentUser currentUser) {
        require(currentUser, Permission.CREATE_TASK);
        requireTaskDesigner(command.designerEmployeeNo(), currentUser, "创建");
        validateReviewerAssignments(command);
        long id = nextId();
        ReviewTask task = ReviewTask.draft(id, command.reviewType(), command.taskName(), command.projectName(),
                command.designerEmployeeNo(), command.designerName(), command.designName(), command.pcbType(), command.expectedCompletedDate(),
                command.expertLeaderEmployeeNo(), command.expertLeaderName(), command.reviewRoles(), command.reviewerAssignments(), command.reviewDescription());
        taskMapper.insert(toRecord(task));
        return toTaskView(task);
    }

    @Transactional
    public TaskView submit(long taskId, List<Long> initialFileIds, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        ReviewTask task = restore(existing);
        requireTaskDesigner(task.designerEmployeeNo(), currentUser, "提交");
        initialFileIds.forEach(task::addInitialFile);
        task.submit();
        if (taskMapper.update(toRecord(task)) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        // 任务提交后才激活 PCB、EMC 评审人员；工艺、结构人员要等对应评审节点真正开启。
        updateActiveReviewers(taskId, initialActiveReviewerEmployeeNos(task));
        appendFlow(task.id(), WorkflowAction.CREATE, currentUser.employeeNo(), "提交评审任务");
        reviewMailNotificationApplicationService.enqueueTaskCreated(task);
        return toTaskView(task);
    }

    @Transactional
    public TaskView updateDraft(long taskId, CreateTaskCommand command, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        if (TaskStatus.DRAFT != TaskStatus.valueOf(existing.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已提交或已产生评审意见的任务不允许编辑");
        }
        requireTaskDesigner(existing.getDesignerEmployeeNo(), currentUser, "编辑");
        if (!existing.getDesignerEmployeeNo().equals(command.designerEmployeeNo())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "编辑任务时不允许变更设计者");
        }
        validateReviewerAssignments(command);
        ReviewTask updated = ReviewTask.draft(taskId, command.reviewType(), command.taskName(), command.projectName(),
                command.designerEmployeeNo(), command.designerName(), command.designName(), command.pcbType(), command.expectedCompletedDate(),
                command.expertLeaderEmployeeNo(), command.expertLeaderName(), command.reviewRoles(), command.reviewerAssignments(), command.reviewDescription());
        existing.setReviewType(updated.reviewType().name()); existing.setTaskName(updated.taskName()); existing.setProjectName(updated.projectName());
        existing.setDesignerName(updated.designerName()); existing.setDesignName(updated.designName()); existing.setPcbType(updated.pcbType());
        existing.setExpectedCompletedDate(updated.expectedCompletedDate()); existing.setExpertLeaderEmployeeNo(updated.expertLeaderEmployeeNo());
        existing.setExpertLeaderName(updated.expertLeaderName()); existing.setReviewRoles(updated.reviewRoles().stream().map(Enum::name).collect(Collectors.joining(",")));
        existing.setReviewerAssignments(TaskReviewerAssignmentCodec.encode(updated.reviewerAssignments()));
        // 草稿不产生待办；assigned_reviewer_employee_nos 仅表示已开启节点的实时处理人。
        existing.setAssignedReviewerEmployeeNos("");
        existing.setReviewDescription(updated.reviewDescription());
        if (taskMapper.updateDraft(existing) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "任务已提交，不允许编辑");
        }
        return toTaskView(updated);
    }

    @Transactional
    public TaskView saveDraftFiles(long taskId, List<Long> initialFileIds, CurrentUser currentUser) {
        ReviewTaskRecord existing = requireRecord(taskId);
        ReviewTask task = restore(existing);
        requireTaskDesigner(task.designerEmployeeNo(), currentUser, "保存草稿文件");
        initialFileIds.forEach(task::addInitialFile);
        if (taskMapper.update(toRecord(task)) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        return toTaskView(task);
    }

    public TaskView get(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord record = requireRecord(taskId);
        ReviewTask task = restore(record);
        if (!canView(task, currentUser)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务");
        }
        List<TaskFlowRecordView> flowRecords = flowMapper.findByTaskId(taskId).stream()
                .map(this::toFlowRecordView)
                .toList();
        return toTaskView(task, flowRecords);
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
                .map(record -> toTaskView(restore(record)))
                .toList();
        int fromIndex = (int) Math.min((long) (validQuery.pageNo() - 1) * validQuery.pageSize(), visible.size());
        int toIndex = Math.min(fromIndex + validQuery.pageSize(), visible.size());
        return new TaskPage(visible.size(), validQuery.pageNo(), validQuery.pageSize(), visible.subList(fromIndex, toIndex));
    }

    private synchronized long nextId() {
        return taskMapper.nextId();
    }

    private List<Long> bindInitialFiles(long taskId, List<String> fileIds, CurrentUser currentUser) {
        return fileApplicationService.bindPendingInitialFiles(taskId, fileIds, currentUser).stream()
                .map(FileApplicationService.FileView::id)
                .toList();
    }

    private TaskView toTaskView(ReviewTask task) {
        return TaskView.from(task, employeeName(task.designerEmployeeNo()), employeeName(task.expertLeaderEmployeeNo()), List.of());
    }

    private TaskView toTaskView(ReviewTask task, List<TaskFlowRecordView> flowRecords) {
        return TaskView.from(task, employeeName(task.designerEmployeeNo()), employeeName(task.expertLeaderEmployeeNo()), flowRecords);
    }

    private String employeeName(String employeeNo) {
        return employeeNo == null || employeeNo.isBlank() ? null : userProfileClient.getByEmployeeNo(employeeNo).displayName();
    }

    private ReviewTaskRecord requireRecord(long taskId) {
        ReviewTaskRecord record = taskMapper.findById(taskId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        return record;
    }

    private void appendFlow(long taskId, WorkflowAction action, String operateEmployeeNo, String comment) {
        TaskFlowRecord record = new TaskFlowRecord();
        record.setId(flowMapper.nextId());
        record.setTaskId(taskId);
        record.setAction(action.name());
        record.setActionName(action.actionName());
        record.setOperateEmployeeNo(operateEmployeeNo);
        record.setComment(comment);
        flowMapper.insert(record);
    }

    private TaskFlowRecordView toFlowRecordView(TaskFlowRecord record) {
        String operatorName = userProfileClient.getByEmployeeNo(record.getOperateEmployeeNo()).displayName();
        return new TaskFlowRecordView(record.getId(), record.getAction(), record.getActionName(), record.getOperateEmployeeNo(),
                operatorName, record.getComment(), record.getCreatedAt());
    }

    private boolean canView(ReviewTask task, CurrentUser currentUser) {
        if (permissionPolicy.canViewAllTasks(currentUser.roles())) {
            return true;
        }
        return permissionPolicy.canViewCurrentTask(currentUser.roles(),
                taskAssignmentAccessMapper.isAssignedToTask(task.id(), currentUser.employeeNo()));
    }

    private void require(CurrentUser currentUser, Permission permission) {
        if (!permissionPolicy.has(currentUser.roles(), permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无对应功能权限");
        }
    }

    private void requireTaskDesigner(String designerEmployeeNo, CurrentUser currentUser, String action) {
        if (designerEmployeeNo == null || designerEmployeeNo.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "任务设计者工号不能为空");
        }
        if (!permissionPolicy.isAdministrator(currentUser.roles())
                && (!currentUser.roles().contains(Role.DESIGNER) || !designerEmployeeNo.equals(currentUser.employeeNo()))) {
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
        Map<ReviewRole, List<String>> reviewersByRole = assignments.stream().collect(Collectors.toMap(
                TaskReviewerAssignment::reviewRole, TaskReviewerAssignment::reviewerEmployeeNos,
                (left, right) -> { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "同一评审角色只能选择一组专家"); }));
        if (!reviewersByRole.keySet().equals(Set.copyOf(roles)) || reviewersByRole.values().stream().anyMatch(List::isEmpty)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "每个已勾选的评审角色都必须选择至少一名专家");
        }
        for (ReviewRole role : roles) {
            Set<String> whitelistEmployeeNos = reviewerWhitelistApplicationService.listAssignableUsers(
                            List.of(ReviewerWhitelistRole.fromReviewRole(role))).stream()
                    .map(ReviewerWhitelistApplicationService.AssignableReviewerView::employeeNo)
                    .collect(Collectors.toSet());
            if (!whitelistEmployeeNos.containsAll(reviewersByRole.get(role))) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "所选专家不属于“" + role.name() + "”角色白名单");
            }
        }
    }

    public record CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, String designerEmployeeNo, String designerName,
                                    String designName, String pcbType, LocalDate expectedCompletedDate, String expertLeaderEmployeeNo,
                                    String expertLeaderName, List<ReviewRole> reviewRoles,
                                    List<TaskReviewerAssignment> reviewerAssignments, String reviewDescription) {
        public CreateTaskCommand {
            reviewRoles = reviewRoles == null ? List.of() : List.copyOf(reviewRoles);
            reviewerAssignments = reviewerAssignments == null ? List.of() : List.copyOf(reviewerAssignments);
        }
        public CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, String designerEmployeeNo, String designerName,
                                 String designName, String pcbType, LocalDate expectedCompletedDate, String expertLeaderEmployeeNo,
                                 String expertLeaderName, List<ReviewRole> reviewRoles, String reviewDescription) {
            this(reviewType, taskName, projectName, designerEmployeeNo, designerName, designName, pcbType, expectedCompletedDate,
                    expertLeaderEmployeeNo, expertLeaderName, reviewRoles, List.of(), reviewDescription);
        }
        public CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, String designerEmployeeNo, String designName, String pcbType) {
            this(reviewType, taskName, projectName, designerEmployeeNo, designerEmployeeNo, designName, pcbType, LocalDate.now(),
                    designerEmployeeNo, designerEmployeeNo, List.of(ReviewRole.PCB_EXPERT), List.of(), null);
        }
    }

    public record TaskView(Long id, ReviewType reviewType, String taskName, String projectName, String designerEmployeeNo, String designerName,
                           String designName, String pcbType, LocalDate expectedCompletedDate, String expertLeaderEmployeeNo, String expertLeaderName,
                           List<ReviewRole> reviewRoles, List<TaskReviewerAssignment> reviewerAssignments, String reviewDescription, String status,
                           List<TaskFlowRecordView> flowRecords) {
        static TaskView from(ReviewTask task, String designerName, String expertLeaderName, List<TaskFlowRecordView> flowRecords) {
            return new TaskView(task.id(), task.reviewType(), task.taskName(), task.projectName(), task.designerEmployeeNo(), designerName,
                    task.designName(), task.pcbType(), task.expectedCompletedDate(), task.expertLeaderEmployeeNo(), expertLeaderName,
                    task.reviewRoles(), task.reviewerAssignments(), task.reviewDescription(), task.status().name(), List.copyOf(flowRecords));
        }
    }

    /** 任务详情中的流程审计记录；邮件投递记录不属于任务详情返回范围。 */
    public record TaskFlowRecordView(Long id, String action, String actionName, String operatorEmployeeNo, String operatorName,
                                     String comment, java.time.LocalDateTime operatedAt) { }

    public record TaskPage(long total, int pageNo, int pageSize, List<TaskView> items) {
        public TaskPage {
            items = List.copyOf(items);
        }
    }

    public record TaskQuery(String keyword, ReviewType reviewType, List<TaskStatus> statuses, Integer pageNo, Integer pageSize, Long taskId) {
        static TaskQuery defaultQuery() {
            return new TaskQuery(null, null, null, 1, 20, null);
        }

        TaskQuery normalized() {
            int normalizedPageNo = pageNo == null ? 1 : pageNo;
            int normalizedPageSize = pageSize == null ? 20 : pageSize;
            if (normalizedPageNo < 1 || normalizedPageSize < 1 || normalizedPageSize > 100) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数不合法");
            }
            List<TaskStatus> normalizedStatuses = statuses == null ? List.of() : statuses.stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            return new TaskQuery(keyword, reviewType, normalizedStatuses, normalizedPageNo, normalizedPageSize, taskId);
        }

        boolean matches(ReviewTask task) {
            return (contains(task.taskName(), keyword)
                    || contains(task.projectName(), keyword)
                    || contains(task.designerName(), keyword))
                    && (reviewType == null || reviewType == task.reviewType())
                    && (statuses == null || statuses.isEmpty() || statuses.contains(task.status()))
                    && (taskId == null || taskId == task.id());
        }

        private static boolean contains(String value, String condition) {
            return condition == null || condition.isBlank() || value.toLowerCase().contains(condition.trim().toLowerCase());
        }
    }

    private ReviewTaskRecord toRecord(ReviewTask task) {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(task.id()); record.setReviewType(task.reviewType().name()); record.setTaskName(task.taskName());
        record.setProjectName(task.projectName()); record.setDesignerEmployeeNo(task.designerEmployeeNo()); record.setDesignerName(task.designerName()); record.setDesignName(task.designName());
        record.setPcbType(task.pcbType()); record.setExpectedCompletedDate(task.expectedCompletedDate()); record.setExpertLeaderEmployeeNo(task.expertLeaderEmployeeNo());
        record.setExpertLeaderName(task.expertLeaderName()); record.setReviewRoles(task.reviewRoles().stream().map(Enum::name).collect(Collectors.joining(",")));
        record.setReviewerAssignments(TaskReviewerAssignmentCodec.encode(task.reviewerAssignments()));
        // 创建、保存草稿时不预先激活未来节点的专家。
        record.setAssignedReviewerEmployeeNos("");
        record.setReviewDescription(task.reviewDescription()); record.setStatus(task.status().name());
        record.setInitialFileIds(task.initialFileIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setVersion(0L); return record;
    }

    private ReviewTask restore(ReviewTaskRecord record) {
        List<Long> fileIds = record.getInitialFileIds() == null || record.getInitialFileIds().isBlank() ? List.of() : java.util.Arrays.stream(record.getInitialFileIds().split(",")).map(Long::valueOf).toList();
        List<ReviewRole> roles = record.getReviewRoles() == null || record.getReviewRoles().isBlank() ? List.of(ReviewRole.PCB_EXPERT)
                : java.util.Arrays.stream(record.getReviewRoles().split(",")).map(ReviewRole::valueOf).toList();
        List<TaskReviewerAssignment> reviewerAssignments = TaskReviewerAssignmentCodec.decode(record.getReviewerAssignments());
        return ReviewTask.restore(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getTaskName(), record.getProjectName(), record.getDesignerEmployeeNo(),
                record.getDesignerName() == null || record.getDesignerName().isBlank() ? record.getDesignerEmployeeNo() : record.getDesignerName(),
                record.getDesignName(), record.getPcbType(), record.getExpectedCompletedDate() == null ? LocalDate.now() : record.getExpectedCompletedDate(),
                record.getExpertLeaderEmployeeNo() == null ? record.getDesignerEmployeeNo() : record.getExpertLeaderEmployeeNo(),
                record.getExpertLeaderName() == null || record.getExpertLeaderName().isBlank() ? record.getExpertLeaderEmployeeNo() : record.getExpertLeaderName(),
                roles, reviewerAssignments, record.getReviewDescription(), TaskStatus.valueOf(record.getStatus()), fileIds);
    }

    private List<String> initialActiveReviewerEmployeeNos(ReviewTask task) {
        if (task.reviewType() != ReviewType.PCB) {
            return List.of();
        }
        List<String> employeeNos = task.reviewerAssignments().stream()
                .filter(assignment -> assignment.reviewRole() == ReviewRole.PCB_EXPERT
                        || assignment.reviewRole() == ReviewRole.EMC_EXPERT)
                .flatMap(assignment -> assignment.reviewerEmployeeNos().stream())
                .distinct()
                .toList();
        // 兼容没有角色—人员映射的旧任务。
        return employeeNos.isEmpty() && task.expertLeaderEmployeeNo() != null ? List.of(task.expertLeaderEmployeeNo()) : employeeNos;
    }

    private void updateActiveReviewers(long taskId, List<String> reviewerEmployeeNos) {
        ReviewTaskRecord update = new ReviewTaskRecord();
        update.setId(taskId);
        update.setAssignedReviewerEmployeeNos(TaskReviewerAssignmentCodec.encodeReviewerEmployeeNos(reviewerEmployeeNos));
        if (taskMapper.updateAssignedReviewerEmployeeNos(update) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
    }
}
