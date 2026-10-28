package com.bms.task.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.application.FileApplicationService;
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
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 编排评审任务创建、提交、查询和可见性校验，协调当前用户上下文、任务领域模型与持久化接口。
 */


@Service
public class TaskApplicationService {
    private final ReviewTaskMapper taskMapper;
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper;
    private final TaskFlowMapper flowMapper;
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService;
    private final ReviewMailNotificationApplicationService reviewMailNotificationApplicationService;
    private final FileApplicationService fileApplicationService;

    @Autowired
    public TaskApplicationService(ReviewTaskMapper taskMapper,
                                  TaskAssignmentAccessMapper taskAssignmentAccessMapper, TaskFlowMapper flowMapper,
                                  ReviewerWhitelistApplicationService reviewerWhitelistApplicationService,
                                  ReviewMailNotificationApplicationService reviewMailNotificationApplicationService,
                                  FileApplicationService fileApplicationService) {
        this.taskMapper = taskMapper;
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
        this.flowMapper = flowMapper;
        this.reviewerWhitelistApplicationService = reviewerWhitelistApplicationService;
        this.reviewMailNotificationApplicationService = reviewMailNotificationApplicationService;
        this.fileApplicationService = fileApplicationService;
    }

    /**
     * 保存任务表单并关联前端预上传的初始文件。文件只以 UUID 传入，实际绑定与任务保存保持同一事务。
     */
    @Transactional
    public TaskView saveNewTask(CreateTaskCommand command, List<String> fileIds,
                                boolean submit, String operatorEmployeeNo, String operatorName) {
        TaskView draft = createDraft(command, operatorEmployeeNo, operatorName);
        List<Long> boundFileIds = bindPendingInitialFiles(draft.id(), fileIds, operatorEmployeeNo, operatorName);
        return finishSavingTask(draft, boundFileIds, submit, operatorEmployeeNo, operatorName);
    }

    /**
     * 编辑草稿并关联本次传入的初始文件。已绑定到当前任务的 UUID 由文件服务幂等处理，不会重复关联。
     */
    @Transactional
    public TaskView saveExistingTask(long taskId, CreateTaskCommand command, List<String> fileIds,
                                     boolean submit, String operatorEmployeeNo, String operatorName) {
        TaskView draft = updateDraftTask(taskId, command, operatorEmployeeNo, operatorName);
        List<Long> boundFileIds = bindPendingInitialFiles(taskId, fileIds, operatorEmployeeNo, operatorName);
        return finishSavingTask(draft, boundFileIds, submit, operatorEmployeeNo, operatorName);
    }

    /**
     * 提交已经保存的草稿任务，并仅将当前阶段应处理的专家写入待办人员字段。
     */
    @Transactional
    public TaskView submitTask(long taskId, List<Long> initialFileIds, String operatorEmployeeNo, String operatorName) {
        ReviewTask task = loadTask(taskId);
        requireDesignerPermission(task.designerEmployeeNo(), operatorEmployeeNo, operatorName, "提交");
        addInitialFiles(task, initialFileIds);
        task.submit();
        ReviewTaskRecord persistedTask = persistSubmittedTask(task);
        activateReviewersAtTaskSubmission(task);
        recordTaskFlow(task.id(), WorkflowAction.CREATE, operatorEmployeeNo, "提交评审任务");
        // 邮件收件人必须以已落库的 reviewer_assignments 为准，不能读取提交过程中的临时对象。
        reviewMailNotificationApplicationService.enqueueTaskCreated(persistedTask);
        return toTaskSummary(task);
    }

    /**
     * 读取任务详情，并附带可追溯的流程操作记录。
     */
    public TaskView getTaskDetail(long taskId, String operatorEmployeeNo, String operatorName) {
        ReviewTaskRecord taskRecord = loadTaskRecord(taskId);
        ReviewTask task = restoreTask(taskRecord);
        if (!canCurrentUserViewTask(task, operatorEmployeeNo, operatorName)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务");
        }
        List<TaskFlowRecordView> flowRecords = flowMapper.findByTaskId(taskId).stream()
                .map(this::toTaskFlowRecordView)
                .toList();
        return toTaskDetailView(task, taskRecord.getMutualCheckReviewerEmployeeNos(), flowRecords, taskRecord.getCreatedAt());
    }

    /** 返回全部符合筛选条件的任务；任务列表不按当前用户做数据隔离。 */
    public List<TaskView> listVisibleTasks() {
        return listTasks(TaskQuery.defaultQuery()).items();
    }

    /** 根据筛选条件分页返回任务列表；该列表是公共查询，不依赖当前登录用户。 */
    public TaskPage listTasks(TaskQuery query) {
        TaskQuery validQuery = (query == null ? TaskQuery.defaultQuery() : query).normalized();
        List<String> statusNames = validQuery.statuses().stream().map(Enum::name).toList();
        String reviewTypeName = validQuery.reviewType() == null ? null : validQuery.reviewType().name();
        long total = taskMapper.countPage(validQuery.normalizedTaskName(), validQuery.normalizedProjectName(),
                validQuery.normalizedDesignerEmployeeNo(), reviewTypeName, statusNames, validQuery.taskId());
        long offset = (long) (validQuery.pageNum() - 1) * validQuery.pageSize();
        List<ReviewTaskRecord> records = total == 0 ? List.of()
                : taskMapper.findPage(validQuery.normalizedTaskName(), validQuery.normalizedProjectName(),
                validQuery.normalizedDesignerEmployeeNo(), reviewTypeName, statusNames, validQuery.taskId(),
                offset, validQuery.pageSize());
        Map<String, String> mutualCheckReviewerNames = findMutualCheckReviewerNames(records);
        List<TaskView> items = records.stream()
                .map(record -> toTaskListItem(record, mutualCheckReviewerNames))
                .toList();
        return new TaskPage(total, validQuery.pageNum(), validQuery.pageSize(), items);
    }

    /** 新建一条尚未派发的任务草稿。 */
    private TaskView createDraft(CreateTaskCommand command, String operatorEmployeeNo, String operatorName) {
        requireDesignerPermission(command.designerEmployeeNo(), operatorEmployeeNo, operatorName, "创建");
        validateReviewerAssignments(command);
        long id = nextTaskId();
        ReviewTask task = ReviewTask.draft(id, command.reviewType(), command.taskName(), command.projectName(),
                command.designerEmployeeNo(), command.designerName(), command.designName(), command.pcbType(), command.expectedCompletedDate(),
                command.expertLeaderEmployeeNo(), command.expertLeaderName(), command.reviewRoles(), command.reviewerAssignments(), command.reviewDescription());
        taskMapper.insert(toTaskRecord(task));
        return toTaskSummary(task);
    }

    /** 更新已有草稿的表单内容；已提交任务不能再通过此入口修改。 */
    private TaskView updateDraftTask(long taskId, CreateTaskCommand command, String operatorEmployeeNo, String operatorName) {
        ReviewTaskRecord existing = loadTaskRecord(taskId);
        if (TaskStatus.DRAFT != TaskStatus.valueOf(existing.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已提交或已产生评审意见的任务不允许编辑");
        }
        requireDesignerPermission(existing.getDesignerEmployeeNo(), operatorEmployeeNo, operatorName, "编辑");
        if (!existing.getDesignerEmployeeNo().equals(command.designerEmployeeNo())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "编辑任务时不允许变更设计者");
        }
        validateReviewerAssignments(command);
        ReviewTask updated = ReviewTask.draft(taskId, command.reviewType(), command.taskName(), command.projectName(),
                command.designerEmployeeNo(), command.designerName(), command.designName(), command.pcbType(), command.expectedCompletedDate(),
                command.expertLeaderEmployeeNo(), command.expertLeaderName(), command.reviewRoles(), command.reviewerAssignments(), command.reviewDescription());
        copyDraftTaskDetails(updated, existing);
        if (taskMapper.updateDraft(existing) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "任务已提交，不允许编辑");
        }
        return toTaskSummary(updated);
    }

    /** 将本次成功绑定的文件编号写入草稿，已存在的文件由领域对象自动去重。 */
    private TaskView addFilesToDraft(long taskId, List<Long> initialFileIds, String operatorEmployeeNo, String operatorName) {
        ReviewTask task = loadTask(taskId);
        requireDesignerPermission(task.designerEmployeeNo(), operatorEmployeeNo, operatorName, "保存草稿文件");
        addInitialFiles(task, initialFileIds);
        if (taskMapper.update(toTaskRecord(task)) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        return toTaskSummary(task);
    }

    private synchronized long nextTaskId() {
        return taskMapper.nextId();
    }

    /** 统一处理“仅保存草稿”与“保存后立即提交”两个页面动作。 */
    private TaskView finishSavingTask(TaskView draft, List<Long> boundFileIds, boolean submit, String operatorEmployeeNo, String operatorName) {
        if (submit) {
            return submitTask(draft.id(), boundFileIds, operatorEmployeeNo, operatorName);
        }
        return boundFileIds.isEmpty() ? draft : addFilesToDraft(draft.id(), boundFileIds, operatorEmployeeNo, operatorName);
    }

    private List<Long> bindPendingInitialFiles(long taskId, List<String> fileIds, String operatorEmployeeNo, String operatorName) {
        return fileApplicationService.bindPendingInitialFiles(taskId, fileIds, operatorEmployeeNo, operatorName).stream()
                .map(FileApplicationService.FileView::id)
                .toList();
    }

    private void addInitialFiles(ReviewTask task, List<Long> initialFileIds) {
        (initialFileIds == null ? List.<Long>of() : initialFileIds).forEach(task::addInitialFile);
    }

    private ReviewTaskRecord persistSubmittedTask(ReviewTask task) {
        ReviewTaskRecord record = toTaskRecord(task);
        if (taskMapper.update(record) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        return record;
    }

    private TaskView toTaskSummary(ReviewTask task) {
        return TaskView.from(task, task.designerName(),
                lookupEmployeeName(task.expertLeaderEmployeeNo(), task.expertLeaderName()), List.of());
    }

    /** 任务列表仅为互检单实际分配人员批量读取白名单姓名，避免逐条查询。 */
    private TaskView toTaskListItem(ReviewTaskRecord record, Map<String, String> mutualCheckReviewerNames) {
        ReviewTask task = restoreTask(record);
        LocalDate createdDate = record.getCreatedAt() == null ? null : record.getCreatedAt().toLocalDate();
        return TaskView.from(task, task.designerName(), task.expertLeaderName(), List.of(), createdDate, record.getCreatedAt(), Map.of(),
                toReviewerViews(record.getMutualCheckReviewerEmployeeNos(), mutualCheckReviewerNames));
    }

    private TaskView toTaskDetailView(ReviewTask task, String mutualCheckReviewerEmployeeNos, List<TaskFlowRecordView> flowRecords,
                                      java.time.LocalDateTime createdAt) {
        List<String> reviewerEmployeeNos = task.reviewerAssignments().stream()
                .flatMap(assignment -> assignment.reviewerEmployeeNos().stream())
                .collect(Collectors.collectingAndThen(Collectors.toCollection(java.util.LinkedHashSet::new), List::copyOf));
        reviewerEmployeeNos = java.util.stream.Stream.concat(reviewerEmployeeNos.stream(),
                        TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(mutualCheckReviewerEmployeeNos).stream())
                .distinct().toList();
        Map<String, String> reviewerNames = reviewerWhitelistApplicationService.findDisplayNamesByEmployeeNos(reviewerEmployeeNos);
        return TaskView.from(task, task.designerName(),
                lookupEmployeeName(task.expertLeaderEmployeeNo(), task.expertLeaderName()), flowRecords,
                createdAt == null ? null : createdAt.toLocalDate(), createdAt, reviewerNames == null ? Map.of() : reviewerNames,
                toReviewerViews(mutualCheckReviewerEmployeeNos, reviewerNames));
    }

    private Map<String, String> findMutualCheckReviewerNames(List<ReviewTaskRecord> records) {
        List<String> employeeNos = records.stream()
                .flatMap(record -> TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(
                        record.getMutualCheckReviewerEmployeeNos()).stream())
                .distinct()
                .toList();
        if (employeeNos.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = reviewerWhitelistApplicationService.findDisplayNamesByEmployeeNos(employeeNos);
        return names == null ? Map.of() : names;
    }

    private List<ReviewerView> toReviewerViews(String reviewerEmployeeNos, Map<String, String> reviewerNames) {
        Map<String, String> safeReviewerNames = reviewerNames == null ? Map.of() : reviewerNames;
        return TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(reviewerEmployeeNos).stream()
                .map(employeeNo -> new ReviewerView(employeeNo, safeReviewerNames.getOrDefault(employeeNo, employeeNo)))
                .toList();
    }

    private String lookupEmployeeName(String employeeNo, String defaultName) {
        return reviewerWhitelistApplicationService.findDisplayNameOrDefault(employeeNo, defaultName);
    }

    private ReviewTaskRecord loadTaskRecord(long taskId) {
        ReviewTaskRecord record = taskMapper.findById(taskId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        return record;
    }

    private ReviewTask loadTask(long taskId) {
        return restoreTask(loadTaskRecord(taskId));
    }

    private void recordTaskFlow(long taskId, WorkflowAction action, String operateEmployeeNo, String comment) {
        TaskFlowRecord record = new TaskFlowRecord();
        record.setId(flowMapper.nextId());
        record.setTaskId(taskId);
        record.setAction(action.name());
        record.setActionName(action.actionName());
        record.setOperateEmployeeNo(operateEmployeeNo);
        record.setComment(comment);
        flowMapper.insert(record);
    }

    private TaskFlowRecordView toTaskFlowRecordView(TaskFlowRecord record) {
        String operatorName = lookupEmployeeName(record.getOperateEmployeeNo(), record.getOperateEmployeeNo());
        return new TaskFlowRecordView(record.getId(), record.getAction(), record.getActionName(), record.getOperateEmployeeNo(),
                operatorName, record.getComment(), record.getCreatedAt());
    }

    private boolean canCurrentUserViewTask(ReviewTask task, String operatorEmployeeNo, String operatorName) {
        return operatorEmployeeNo.equals(task.designerEmployeeNo())
                || operatorEmployeeNo.equals(task.expertLeaderEmployeeNo())
                || taskAssignmentAccessMapper.isAssignedToTask(task.id(), operatorEmployeeNo);
    }

    private void requireDesignerPermission(String designerEmployeeNo, String operatorEmployeeNo, String operatorName, String action) {
        if (designerEmployeeNo == null || designerEmployeeNo.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "任务设计者工号不能为空");
        }
        if (!designerEmployeeNo.equals(operatorEmployeeNo)) {
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
                           List<ReviewRole> reviewRoles, List<ReviewerAssignmentView> reviewerAssignments, String reviewDescription, String status,
                           @Schema(description = "互检单实际分配人员，包含工号和姓名；任务完成后仍保留用于追溯") List<ReviewerView> mutualCheckReviewers,
                           List<TaskFlowRecordView> flowRecords,
                           @Schema(description = "任务创建日期，格式 yyyy-MM-dd", example = "2026-10-19")
                           @JsonInclude(JsonInclude.Include.NON_NULL) @JsonFormat(pattern = "yyyy-MM-dd") LocalDate createdDate,
                           @Schema(description = "任务创建时间，任务详情和任务列表返回，格式 yyyy-MM-dd HH:mm:ss", example = "2026-10-19 15:30:00")
                           @JsonInclude(JsonInclude.Include.NON_NULL) @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") java.time.LocalDateTime createdTime) {
        static TaskView from(ReviewTask task, String designerName, String expertLeaderName, List<TaskFlowRecordView> flowRecords) {
            return from(task, designerName, expertLeaderName, flowRecords, (LocalDate) null);
        }

        static TaskView from(ReviewTask task, String designerName, String expertLeaderName, List<TaskFlowRecordView> flowRecords,
                             LocalDate createdDate) {
            return from(task, designerName, expertLeaderName, flowRecords, createdDate, Map.of(), List.of());
        }

        static TaskView from(ReviewTask task, String designerName, String expertLeaderName, List<TaskFlowRecordView> flowRecords,
                             Map<String, String> reviewerNames) {
            return from(task, designerName, expertLeaderName, flowRecords, (LocalDate) null, reviewerNames, List.of());
        }

        static TaskView from(ReviewTask task, String designerName, String expertLeaderName, List<TaskFlowRecordView> flowRecords,
                             Map<String, String> reviewerNames, List<ReviewerView> mutualCheckReviewers) {
            return from(task, designerName, expertLeaderName, flowRecords, (LocalDate) null, reviewerNames, mutualCheckReviewers);
        }

        private static TaskView from(ReviewTask task, String designerName, String expertLeaderName, List<TaskFlowRecordView> flowRecords,
                                     LocalDate createdDate, Map<String, String> reviewerNames,
                                     List<ReviewerView> mutualCheckReviewers) {
            return from(task, designerName, expertLeaderName, flowRecords, createdDate, null, reviewerNames, mutualCheckReviewers);
        }

        private static TaskView from(ReviewTask task, String designerName, String expertLeaderName, List<TaskFlowRecordView> flowRecords,
                                     LocalDate createdDate, java.time.LocalDateTime createdTime, Map<String, String> reviewerNames,
                                     List<ReviewerView> mutualCheckReviewers) {
            return new TaskView(task.id(), task.reviewType(), task.taskName(), task.projectName(), task.designerEmployeeNo(), designerName,
                    task.designName(), task.pcbType(), task.expectedCompletedDate(), task.expertLeaderEmployeeNo(), expertLeaderName,
                    task.reviewRoles(), task.reviewerAssignments().stream()
                    .map(assignment -> ReviewerAssignmentView.from(assignment, reviewerNames)).toList(),
                    task.reviewDescription(), task.status().name(), List.copyOf(mutualCheckReviewers), List.copyOf(flowRecords), createdDate, createdTime);
        }
    }

    /** 前端同时使用评审角色编码和中文名称，人员工号保持原有结构。 */
    public record ReviewerAssignmentView(ReviewRole reviewRole,
                                         @Schema(description = "评审角色中文名称", example = "硬件评审") String reviewRoleName,
                                         List<String> reviewerEmployeeNos,
                                         @Schema(description = "评审人明细，任务详情中包含员工工号及白名单姓名") List<ReviewerView> reviewers) {
        public ReviewerAssignmentView(ReviewRole reviewRole, String reviewRoleName, List<String> reviewerEmployeeNos) {
            this(reviewRole, reviewRoleName, reviewerEmployeeNos, List.of());
        }

        static ReviewerAssignmentView from(TaskReviewerAssignment assignment) {
            return new ReviewerAssignmentView(assignment.reviewRole(), assignment.reviewRole().displayName(),
                    assignment.reviewerEmployeeNos(), List.of());
        }

        static ReviewerAssignmentView from(TaskReviewerAssignment assignment, Map<String, String> reviewerNames) {
            List<String> employeeNos = assignment.reviewerEmployeeNos();
            if (reviewerNames == null || reviewerNames.isEmpty()) {
                return from(assignment);
            }
            return new ReviewerAssignmentView(assignment.reviewRole(), assignment.reviewRole().displayName(), employeeNos,
                    employeeNos.stream().map(employeeNo -> new ReviewerView(employeeNo,
                            reviewerNames.getOrDefault(employeeNo, employeeNo))).toList());
        }
    }

    public record ReviewerView(@Schema(description = "评审人工号") String employeeNo,
                               @Schema(description = "评审人姓名") String reviewerName) { }

    /** 任务详情中的流程审计记录；邮件投递记录不属于任务详情返回范围。 */
    public record TaskFlowRecordView(Long id, String action, String actionName, String operatorEmployeeNo, String operatorName,
                                     String comment, java.time.LocalDateTime operatedAt) { }

    /** 分页响应字段与前端约定为 pageNum、pageSize、total。 */
    public record TaskPage(long total, int pageNum, int pageSize, List<TaskView> items) {
        public TaskPage {
            items = List.copyOf(items);
        }
    }

    public record TaskQuery(String taskName, String projectName, String designerEmployeeNo, ReviewType reviewType,
                            List<TaskStatus> statuses, Integer pageNum, Integer pageSize, Long taskId) {
        static TaskQuery defaultQuery() {
            return new TaskQuery(null, null, null, null, null, 1, 20, null);
        }

        TaskQuery normalized() {
            int normalizedPageNum = pageNum == null ? 1 : pageNum;
            int normalizedPageSize = pageSize == null ? 20 : pageSize;
            if (normalizedPageNum < 1 || normalizedPageSize < 1 || normalizedPageSize > 100) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数不合法");
            }
            List<TaskStatus> normalizedStatuses = statuses == null ? List.of() : statuses.stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            return new TaskQuery(normalizedTaskName(), normalizedProjectName(), normalizedDesignerEmployeeNo(), reviewType,
                    normalizedStatuses, normalizedPageNum, normalizedPageSize, taskId);
        }

        String normalizedTaskName() {
            return normalizeText(taskName);
        }

        String normalizedProjectName() {
            return normalizeText(projectName);
        }

        String normalizedDesignerEmployeeNo() {
            return normalizeText(designerEmployeeNo);
        }

        boolean matches(ReviewTask task) {
            return contains(task.taskName(), taskName)
                    && contains(task.projectName(), projectName)
                    && (designerEmployeeNo == null || designerEmployeeNo.isBlank()
                    || designerEmployeeNo.trim().equals(task.designerEmployeeNo()))
                    && (reviewType == null || reviewType == task.reviewType())
                    && (statuses == null || statuses.isEmpty() || statuses.contains(task.status()))
                    && (taskId == null || taskId == task.id());
        }

        private static String normalizeText(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private static boolean contains(String value, String condition) {
            return condition == null || condition.isBlank() || value.toLowerCase().contains(condition.trim().toLowerCase());
        }
    }

    /** 将领域任务转换为完整的任务持久化记录。 */
    private ReviewTaskRecord toTaskRecord(ReviewTask task) {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(task.id());
        record.setReviewType(task.reviewType().name());
        record.setTaskName(task.taskName());
        record.setProjectName(task.projectName());
        record.setDesignerEmployeeNo(task.designerEmployeeNo());
        record.setDesignerName(task.designerName());
        record.setDesignName(task.designName());
        record.setPcbType(task.pcbType());
        record.setExpectedCompletedDate(task.expectedCompletedDate());
        record.setExpertLeaderEmployeeNo(task.expertLeaderEmployeeNo());
        record.setExpertLeaderName(task.expertLeaderName());
        record.setReviewRoles(task.reviewRoles().stream().map(Enum::name).collect(Collectors.joining(",")));
        record.setReviewerAssignments(TaskReviewerAssignmentCodec.encode(task.reviewerAssignments()));
        // 创建、保存草稿时不预先激活未来节点的专家。
        record.setAssignedReviewerEmployeeNos("");
        record.setMutualCheckReviewerEmployeeNos("");
        record.setReviewDescription(task.reviewDescription());
        record.setStatus(task.status().name());
        record.setInitialFileIds(task.initialFileIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setVersion(0L);
        return record;
    }

    /** 将数据库记录恢复为领域任务；空字段按存量数据兼容规则补足。 */
    private ReviewTask restoreTask(ReviewTaskRecord record) {
        List<Long> fileIds = parseInitialFileIds(record.getInitialFileIds());
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

    private List<Long> parseInitialFileIds(String persistedFileIds) {
        if (persistedFileIds == null || persistedFileIds.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(persistedFileIds.split(",")).map(Long::valueOf).toList();
    }

    /**
     * 提交 PCB 任务时只启动 PCB、EMC 两类专家；工艺和结构专家必须等待相应节点开启。
     * 原理图任务先进入互检单分配，因此提交时不激活专家。
     */
    private void activateReviewersAtTaskSubmission(ReviewTask task) {
        replaceActiveReviewerEmployeeNos(task.id(), reviewerEmployeeNosToActivateAtSubmission(task));
    }

    private List<String> reviewerEmployeeNosToActivateAtSubmission(ReviewTask task) {
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

    private void replaceActiveReviewerEmployeeNos(long taskId, List<String> reviewerEmployeeNos) {
        ReviewTaskRecord update = new ReviewTaskRecord();
        update.setId(taskId);
        update.setAssignedReviewerEmployeeNos(TaskReviewerAssignmentCodec.encodeReviewerEmployeeNos(reviewerEmployeeNos));
        if (taskMapper.updateAssignedReviewerEmployeeNos(update) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
    }

    /** 将允许编辑的表单字段复制到已有草稿记录，当前待办人员始终清空。 */
    private void copyDraftTaskDetails(ReviewTask task, ReviewTaskRecord target) {
        target.setReviewType(task.reviewType().name());
        target.setTaskName(task.taskName());
        target.setProjectName(task.projectName());
        target.setDesignerName(task.designerName());
        target.setDesignName(task.designName());
        target.setPcbType(task.pcbType());
        target.setExpectedCompletedDate(task.expectedCompletedDate());
        target.setExpertLeaderEmployeeNo(task.expertLeaderEmployeeNo());
        target.setExpertLeaderName(task.expertLeaderName());
        target.setReviewRoles(task.reviewRoles().stream().map(Enum::name).collect(Collectors.joining(",")));
        target.setReviewerAssignments(TaskReviewerAssignmentCodec.encode(task.reviewerAssignments()));
        target.setAssignedReviewerEmployeeNos("");
        target.setReviewDescription(task.reviewDescription());
    }
}
