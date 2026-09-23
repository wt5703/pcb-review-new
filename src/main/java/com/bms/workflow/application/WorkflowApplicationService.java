package com.bms.workflow.application;

import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.application.FileApplicationService;
import com.bms.file.domain.FileUploadScene;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.file.domain.FileCategory;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import com.bms.review.application.TaskCheckItemApplicationService;
import com.bms.review.application.ReviewerAssignmentService;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.OpinionStatus;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.domain.ReviewRole;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.domain.WorkflowAction;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import io.swagger.v3.oas.annotations.media.Schema;
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
    private final ReviewOpinionMapper opinionMapper;
    private final ReviewFileMapper fileMapper;
    private final TaskCheckItemApplicationService checkItemApplicationService;
    private final TaskFlowMapper flowMapper;
    private final OperationAuditMapper auditMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final TaskArchiveApplicationService taskArchiveApplicationService;
    private final ReviewerAssignmentService reviewerAssignmentService;
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService;
    private final FileApplicationService fileApplicationService;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public WorkflowApplicationService(ReviewTaskMapper taskMapper, ReviewOpinionMapper opinionMapper,
                                      ReviewFileMapper fileMapper, TaskCheckItemApplicationService checkItemApplicationService, TaskFlowMapper flowMapper,
                                      OperationAuditMapper auditMapper, OutboxEventMapper outboxEventMapper,
                                      TaskArchiveApplicationService taskArchiveApplicationService,
                                      ReviewerAssignmentService reviewerAssignmentService,
                                      ReviewerWhitelistApplicationService reviewerWhitelistApplicationService,
                                      FileApplicationService fileApplicationService) {
        this.taskMapper = taskMapper;
        this.opinionMapper = opinionMapper;
        this.fileMapper = fileMapper;
        this.checkItemApplicationService = checkItemApplicationService;
        this.flowMapper = flowMapper;
        this.auditMapper = auditMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.taskArchiveApplicationService = taskArchiveApplicationService;
        this.reviewerAssignmentService = reviewerAssignmentService;
        this.reviewerWhitelistApplicationService = reviewerWhitelistApplicationService;
        this.fileApplicationService = fileApplicationService;
    }

    /**
     * 查询当前任务节点可分配的职责及人员。流程域只负责从状态决定“可分配什么职责”，
     * 白名单域再依据职责把员工工号解析为可用于 reviewerIds 的用户账号。
     */
    public List<AssignableReviewerRoleView> listAssignableReviewers(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTask(taskId);
        List<AssignableRoleRule> rules = assignableRoleRules(task);
        if (rules.isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前流程节点不需要分配人员");
        }
        for (AssignableRoleRule rule : rules) {
            if (!permissionPolicy.has(currentUser.roles(), rule.assignmentRole().assignmentPermission())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无当前流程节点的人员分配权限");
            }
        }
        return rules.stream().map(rule -> new AssignableReviewerRoleView(rule.assignmentRole(),
                reviewerWhitelistApplicationService.listAssignableUsers(rule.whitelistRoles()))).toList();
    }

    @Transactional
    public WorkflowView transition(long taskId, WorkflowAction action, String comment, CurrentUser currentUser) {
        return transition(taskId, new TransitionCommand(action, comment, null, List.of(), List.of()), currentUser);
    }

    /**
     * 对外流程请求使用 actions 数组。除了 PCB 的工艺、结构评审可在同一事务中同时开启，
     * 其余业务动作必须单独提交，避免一次请求跨越多个无关节点。
     */
    @Transactional
    public WorkflowView transition(long taskId, TransitionBatchCommand command, CurrentUser currentUser) {
        if (command == null || command.actions().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
        }
        if (command.actions().size() == 1) {
            if (isPcbStageReviewAction(command.actions().get(0))) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "PCB 工艺评审和结构评审必须同时提交 START_PCB_STRUCTURE_REVIEW、START_PCB_PROCESS_REVIEW");
            }
            return transition(taskId, new TransitionCommand(command.actions().get(0), command.comment(), command.assignedRole(),
                    command.reviewerIds(), command.stageFiles()), currentUser);
        }
        if (!isPcbStageReviewPair(command.actions())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "一次只能提交一个流程动作；仅 PCB 工艺评审和结构评审允许同时提交");
        }
        if (command.assignedRole() != null || !command.reviewerIds().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "开启工艺和结构评审时不能同时分配人员");
        }
        // 固定先登记并开启结构评审，再开启工艺评审；整个方法在同一事务中执行，任一步失败均会回滚。
        WorkflowView structureView = transition(taskId, new TransitionCommand(WorkflowAction.START_PCB_STRUCTURE_REVIEW,
                command.comment(), null, List.of(), command.stageFiles()), currentUser);
        WorkflowView processView = transition(taskId, new TransitionCommand(WorkflowAction.START_PCB_PROCESS_REVIEW,
                command.comment(), null, List.of(), List.of()), currentUser);
        return new WorkflowView(taskId, structureView.fromStatus(), processView.toStatus(), List.of(), structureView.stageFiles());
    }

    private boolean isPcbStageReviewPair(List<WorkflowAction> actions) {
        return actions.size() == 2
                && actions.contains(WorkflowAction.START_PCB_STRUCTURE_REVIEW)
                && actions.contains(WorkflowAction.START_PCB_PROCESS_REVIEW)
                && actions.stream().distinct().count() == 2;
    }

    private boolean isPcbStageReviewAction(WorkflowAction action) {
        return action == WorkflowAction.START_PCB_STRUCTURE_REVIEW || action == WorkflowAction.START_PCB_PROCESS_REVIEW;
    }

    /**
     * @author 王涛
     * @date 2026-09-22
     * @description 统一编排一次流程推进：调用方可按当前动作附带待分配人员或公司资源服务已上传的阶段文件引用；二进制上传和文件元数据登记仍分别由文件域负责。
     */
    @Transactional
    public WorkflowView transition(long taskId, TransitionCommand command, CurrentUser currentUser) {
        if (command == null || command.action() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
        }
        ReviewTaskRecord task = requireTask(taskId);
        if (TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许重新打开或流转");
        }
        WorkflowAction action = command.action();
        if (action == WorkflowAction.CREATE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CREATE 只能通过任务提交接口执行，不能调用流程推进接口");
        }
        validatePayload(task, action, command);
        validateActionPrerequisites(task, action);
        List<FileApplicationService.FileView> registeredFiles = registerStageFiles(taskId, command.stageFiles(), currentUser);
        List<ReviewerAssignmentService.ReviewerView> assignedReviewers = assignReviewers(taskId, command, currentUser);
        TaskStatus target = targetStatus(task, action, currentUser);
        validateStageFilePresence(task, action);
        if (action == WorkflowAction.FINISH) {
            checkItemApplicationService.materializeActiveTaskItems(taskId);
        }
        String fromStatus = task.getStatus();
        task.setStatus(target.name());
        if (taskMapper.update(task) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        appendHistory(taskId, fromStatus, target.name(), action, currentUser.id(), command.comment());
        auditMapper.insert(new OperationAuditRecord("REVIEW_TASK", taskId, "WORKFLOW_" + action.name(), currentUser.id(),
                fromStatus + " -> " + target.name()));
        outboxEventMapper.insert(new OutboxEventRecord("TASK_STATUS_CHANGED", "REVIEW_TASK", taskId,
                "{\"taskId\":" + taskId + ",\"action\":\"" + action.name() + "\",\"toStatus\":\"" + target.name() + "\"}", "PENDING"));
        if (action == WorkflowAction.FINISH) {
            taskArchiveApplicationService.archive(task);
        }
        return new WorkflowView(taskId, TaskStatus.valueOf(fromStatus), target, assignedReviewers, registeredFiles);
    }

    private List<ReviewerAssignmentService.ReviewerView> assignReviewers(long taskId, TransitionCommand command, CurrentUser currentUser) {
        if (command.assignedRole() == null) {
            return List.of();
        }
        return reviewerAssignmentService.assign(taskId, command.assignedRole(), command.reviewerIds(), currentUser);
    }

    private List<FileApplicationService.FileView> registerStageFiles(long taskId, List<StageFileCommand> stageFiles, CurrentUser currentUser) {
        return stageFiles.stream().map(file -> fileApplicationService.registerStageFile(taskId, file.scene(),
                new FileApplicationService.FileReferenceCommand(file.fileId(), file.fileName(), file.fileSize(), file.md5(), null),
                file.fileKind(), currentUser)).toList();
    }

    private void validatePayload(ReviewTaskRecord task, WorkflowAction action, TransitionCommand command) {
        boolean requiresAssignment = action == WorkflowAction.START_PCB_MATUAL_REVIEW
                || action == WorkflowAction.START_SCHEMATIC_MATUAL_REVIEW
                || action == WorkflowAction.START_SCHEMATIC_EXPERT_REVIEW;
        if (requiresAssignment && (command.assignedRole() == null || command.reviewerIds().isEmpty())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "进入该评审节点时必须指定至少一名评审人员");
        }
        if (!requiresAssignment && (command.assignedRole() != null || !command.reviewerIds().isEmpty())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持分配评审人员");
        }
        if (command.assignedRole() != null && !isExpectedAssignmentRole(action, command.assignedRole())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评审人员职责与当前流程动作不匹配");
        }
        boolean isPcbStageReview = action == WorkflowAction.START_PCB_PROCESS_REVIEW
                || action == WorkflowAction.START_PCB_STRUCTURE_REVIEW;
        if (!command.stageFiles().isEmpty() && !supportsStageFiles(action)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持登记阶段文件");
        }
        if (isPcbStageReview && command.stageFiles().stream().anyMatch(file -> file.scene() != FileUploadScene.PROCESS_REVIEW)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "开启工艺/结构评审时仅允许登记 PROCESS_REVIEW 场景文件");
        }
        if (action == WorkflowAction.START_PCB_MATUAL_ASSIGNMENT
                && command.stageFiles().stream().anyMatch(file -> file.scene() != FileUploadScene.PCB_REVIEW)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "开启互检单分配时仅允许登记 PCB_REVIEW 场景的最新文件");
        }
        if ((action == WorkflowAction.START_SCHEMATIC_EXPERT_ASSIGNMENT || action == WorkflowAction.PREPARE_FINISH)
                && command.stageFiles().stream().anyMatch(file -> file.scene() != FileUploadScene.SCHEMATIC_REVIEW)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前原理图流程动作仅允许登记 SCHEMATIC_REVIEW 场景的最新文件");
        }
        if (action == WorkflowAction.PREPARE_FINISH && ReviewType.PCB.name().equals(task.getReviewType())
                && !command.stageFiles().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "PCB 准备结束不接收阶段文件，请在互检单分配前上传最新 PCB 文件");
        }
    }

    private boolean supportsStageFiles(WorkflowAction action) {
        return action == WorkflowAction.START_PCB_STRUCTURE_REVIEW
                || action == WorkflowAction.START_PCB_PROCESS_REVIEW
                || action == WorkflowAction.START_PCB_MATUAL_ASSIGNMENT
                || action == WorkflowAction.START_SCHEMATIC_EXPERT_ASSIGNMENT
                || action == WorkflowAction.PREPARE_FINISH;
    }

    private boolean isExpectedAssignmentRole(WorkflowAction action, ReviewRole role) {
        return switch (action) {
            case START_PCB_MATUAL_REVIEW -> role == ReviewRole.PCB_MUTUAL_CHECK;
            case START_SCHEMATIC_MATUAL_REVIEW -> role == ReviewRole.SCHEMATIC_MUTUAL_CHECK;
            case START_SCHEMATIC_EXPERT_REVIEW -> role == ReviewRole.SCHEMATIC_HARDWARE_EXPERT || role == ReviewRole.SCHEMATIC_OTHER_EXPERT;
            default -> false;
        };
    }

    private List<AssignableRoleRule> assignableRoleRules(ReviewTaskRecord task) {
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        if (reviewType == ReviewType.PCB && status == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT) {
            // 互检属于 PCB 工作范畴，人员来源使用维护中的 PCB 评审白名单。
            return List.of(new AssignableRoleRule(ReviewRole.PCB_MUTUAL_CHECK, List.of(ReviewRole.PCB_EXPERT)));
        }
        if (reviewType == ReviewType.SCHEMATIC
                && status == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT) {
            // 原理图互检可由各专业白名单人员承担，最终任务职责统一记为原理图互检。
            return List.of(new AssignableRoleRule(ReviewRole.SCHEMATIC_MUTUAL_CHECK, List.of(
                    ReviewRole.HARDWARE_EXPERT, ReviewRole.EMC_EXPERT, ReviewRole.PCB_EXPERT,
                    ReviewRole.PROCESS_EXPERT, ReviewRole.STRUCTURE_EXPERT)));
        }
        if (reviewType == ReviewType.SCHEMATIC && status == TaskStatus.SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT) {
            return List.of(
                    new AssignableRoleRule(ReviewRole.SCHEMATIC_HARDWARE_EXPERT, List.of(ReviewRole.HARDWARE_EXPERT)),
                    new AssignableRoleRule(ReviewRole.SCHEMATIC_OTHER_EXPERT, List.of(
                            ReviewRole.EMC_EXPERT, ReviewRole.PROCESS_EXPERT, ReviewRole.STRUCTURE_EXPERT)));
        }
        return List.of();
    }

    private TaskStatus targetStatus(ReviewTaskRecord task, WorkflowAction action, CurrentUser currentUser) {
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        return switch (action) {
            case CREATE -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CREATE 不能调用流程推进接口");
            case START_PCB_STRUCTURE_REVIEW -> requireDesignerTransition(task, reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_EXPERT_REVIEWING || current == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING),
                    TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING, currentUser);
            case START_PCB_PROCESS_REVIEW -> requireDesignerTransition(task, reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_EXPERT_REVIEWING || current == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING),
                    TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING, currentUser);
            case START_PCB_MATUAL_ASSIGNMENT -> requireDesignerTransition(task, reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_EXPERT_REVIEWING || current == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING),
                    TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT, currentUser);
            case START_PCB_MATUAL_REVIEW -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT,
                    TaskStatus.MUTUAL_CHECK_REVIEWING, currentUser, Permission.ASSIGN_PCB_MUTUAL_CHECK);
            case START_SCHEMATIC_MATUAL_REVIEW -> requireTransition(reviewType == ReviewType.SCHEMATIC
                            && current == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT,
                    TaskStatus.MUTUAL_CHECK_REVIEWING, currentUser, Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK);
            case START_SCHEMATIC_EXPERT_ASSIGNMENT -> requireDesignerTransition(task,
                    reviewType == ReviewType.SCHEMATIC && current == TaskStatus.MUTUAL_CHECK_REVIEWING,
                    TaskStatus.SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT, currentUser);
            case START_SCHEMATIC_EXPERT_REVIEW -> requireTransition(reviewType == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT,
                    TaskStatus.SCHEMATIC_REVIEWING, currentUser, Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT);
            case PREPARE_FINISH -> requireDesignerTransition(task,
                    (reviewType == ReviewType.PCB && current == TaskStatus.MUTUAL_CHECK_REVIEWING)
                            || (reviewType == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_REVIEWING),
                    current, currentUser);
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

    private TaskStatus requireDesignerTransition(ReviewTaskRecord task, boolean allowedStatus, TaskStatus target,
                                                 CurrentUser currentUser) {
        if (!allowedStatus) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许执行该流程动作");
        }
        if (!task.getDesignerId().equals(currentUser.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者可以执行当前流程动作");
        }
        return target;
    }

    /**
     * 按动作核对前序意见。工艺、结构两个分支分别只要求专家评审意见闭环，
     * 所以其中一个分支产生的新意见不会阻塞另一个分支的启动。
     */
    private void validateActionPrerequisites(ReviewTaskRecord task, WorkflowAction action) {
        switch (action) {
            case START_PCB_STRUCTURE_REVIEW, START_PCB_PROCESS_REVIEW ->
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.EXPERT_REVIEW), "专家评审意见尚未全部确认通过，不能开启工艺或结构评审");
            case START_PCB_MATUAL_ASSIGNMENT ->
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.EXPERT_REVIEW, OpinionSourceType.PROCESS_REVIEW,
                            OpinionSourceType.STRUCTURE_REVIEW), "专家、工艺或结构评审意见尚未全部确认通过，不能开启互检单分配");
            case START_SCHEMATIC_EXPERT_ASSIGNMENT ->
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.MUTUAL_CHECK_ITEM, OpinionSourceType.MUTUAL_EXTRA),
                            "互检单意见尚未全部确认通过，不能开启硬件专家分配");
            case PREPARE_FINISH -> {
                if (ReviewType.PCB.name().equals(task.getReviewType())) {
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.MUTUAL_CHECK_ITEM, OpinionSourceType.MUTUAL_EXTRA),
                            "互检单意见尚未全部确认通过，不能准备结束任务");
                } else {
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.EXPERT_REVIEW),
                            "原理图专家评审意见尚未全部确认通过，不能准备结束任务");
                }
            }
            case FINISH -> requireOpinionsPassed(task.getId(), List.of(), "存在未确认通过的意见，不能结束任务");
            default -> { }
        }
    }

    /** 阶段文件可以先通过文件域上传，也可以随 transition 登记；无论哪种方式都必须已存在对应文件。 */
    private void validateStageFilePresence(ReviewTaskRecord task, WorkflowAction action) {
        FileCategory category = switch (action) {
            case START_PCB_PROCESS_REVIEW -> FileCategory.PROCESS_REVIEW;
            case START_PCB_STRUCTURE_REVIEW -> FileCategory.STRUCTURE_REVIEW;
            case START_PCB_MATUAL_ASSIGNMENT -> FileCategory.PCB_REVIEW;
            case START_SCHEMATIC_EXPERT_ASSIGNMENT -> FileCategory.SCHEMATIC_REVIEW;
            case PREPARE_FINISH -> ReviewType.SCHEMATIC.name().equals(task.getReviewType()) ? FileCategory.SCHEMATIC_REVIEW : null;
            default -> null;
        };
        if (category != null && fileMapper.findLatestByTaskIdAndCategory(task.getId(), category.name()).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT,
                    missingFileMessage(category));
        }
    }

    private String missingFileMessage(FileCategory category) {
        return switch (category) {
            case PROCESS_REVIEW -> "请先上传工艺图文件";
            case STRUCTURE_REVIEW -> "请先上传结构图文件";
            case PCB_REVIEW -> "请先上传最新 PCB 评审文件";
            case SCHEMATIC_REVIEW -> "请先上传最新原理图评审文件";
            default -> "请先上传当前流程所需文件";
        };
    }

    /** sources 为空时表示该任务全部意见都必须闭环。 */
    private void requireOpinionsPassed(long taskId, List<OpinionSourceType> sources, String message) {
        List<com.bms.review.infrastructure.ReviewOpinionRecord> opinions = opinionMapper.findByTaskId(taskId);
        boolean passed = (opinions == null ? List.<com.bms.review.infrastructure.ReviewOpinionRecord>of() : opinions).stream()
                .filter(opinion -> sources.isEmpty() || sources.stream().anyMatch(source -> source.name().equals(opinion.getSourceType())))
                .allMatch(opinion -> OpinionStatus.CONFIRMED_PASS.name().equals(opinion.getStatus())
                        || OpinionStatus.WITHDRAWN.name().equals(opinion.getStatus()));
        if (!passed) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, message);
        }
    }

    private TaskStatus requireFinishTransition(ReviewType type, TaskStatus current, CurrentUser currentUser) {
        Permission permission = type == ReviewType.PCB ? Permission.FINISH_PCB_TASK : Permission.FINISH_SCHEMATIC_TASK;
        boolean readyToFinish = (type == ReviewType.PCB && current == TaskStatus.MUTUAL_CHECK_REVIEWING)
                || (type == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_REVIEWING);
        return requireTransition(readyToFinish, TaskStatus.FINISHED, currentUser, permission);
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

    public record TransitionCommand(WorkflowAction action, String comment, ReviewRole assignedRole, List<Long> reviewerIds,
                                    List<StageFileCommand> stageFiles) {
        public TransitionCommand {
            if (reviewerIds != null && reviewerIds.stream().anyMatch(id -> id == null)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评审人员用户 ID 不能为空");
            }
            if (stageFiles != null && stageFiles.stream().anyMatch(file -> file == null)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "阶段文件不能为空");
            }
            reviewerIds = reviewerIds == null ? List.of() : List.copyOf(reviewerIds);
            stageFiles = stageFiles == null ? List.of() : List.copyOf(stageFiles);
        }
    }

    /** 前端 transition 请求模型；只有 START_PCB_STRUCTURE_REVIEW + START_PCB_PROCESS_REVIEW 可同时出现。 */
    public record TransitionBatchCommand(List<WorkflowAction> actions, String comment, ReviewRole assignedRole,
                                         List<Long> reviewerIds, List<StageFileCommand> stageFiles) {
        public TransitionBatchCommand {
            if (actions == null || actions.isEmpty() || actions.stream().anyMatch(action -> action == null)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
            }
            actions = List.copyOf(actions);
            reviewerIds = reviewerIds == null ? List.of() : List.copyOf(reviewerIds);
            stageFiles = stageFiles == null ? List.of() : List.copyOf(stageFiles);
        }
    }

    public record StageFileCommand(FileUploadScene scene, String fileId, String fileName, long fileSize, String md5, String fileKind) {
        public StageFileCommand(FileUploadScene scene, String fileId, String fileName, long fileSize, String md5) {
            this(scene, fileId, fileName, fileSize, md5, null);
        }
        public StageFileCommand {
            if (scene == null || fileId == null || fileId.isBlank() || fileName == null || fileName.isBlank() || fileSize < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "阶段文件参数不合法");
            }
        }
    }

    public record WorkflowView(Long taskId, TaskStatus fromStatus, TaskStatus toStatus,
                               List<ReviewerAssignmentService.ReviewerView> assignedReviewers,
                               List<FileApplicationService.FileView> stageFiles) {
        public WorkflowView {
            assignedReviewers = List.copyOf(assignedReviewers);
            stageFiles = List.copyOf(stageFiles);
        }
    }

    @Schema(description = "当前流程节点的一类可分配职责及对应候选人员")
    public record AssignableReviewerRoleView(
            @Schema(description = "提交流程时 assignedRole 应传的职责") ReviewRole reviewRole,
            @Schema(description = "该职责下可分配人员；userId 可直接填入 transitions 的 reviewerIds")
            List<ReviewerWhitelistApplicationService.AssignableReviewerView> reviewers) { }

    private record AssignableRoleRule(ReviewRole assignmentRole, List<ReviewRole> whitelistRoles) { }
}
