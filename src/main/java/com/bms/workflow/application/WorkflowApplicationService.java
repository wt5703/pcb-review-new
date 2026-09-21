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
import com.bms.identity.domain.Role;
import com.bms.file.domain.FileCategory;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import com.bms.review.application.TaskCheckItemApplicationService;
import com.bms.review.application.ReviewerAssignmentService;
import com.bms.review.domain.OpinionStatus;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerProcessStatus;
import com.bms.review.domain.ReviewerProgress;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.domain.CompletionPolicy;
import com.bms.workflow.domain.WorkflowAction;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
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
    private final TaskArchiveApplicationService taskArchiveApplicationService;
    private final ReviewerAssignmentService reviewerAssignmentService;
    private final FileApplicationService fileApplicationService;
    private final CompletionPolicy completionPolicy = new CompletionPolicy();
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public WorkflowApplicationService(ReviewTaskMapper taskMapper, TaskReviewerMapper reviewerMapper, ReviewOpinionMapper opinionMapper,
                                      ReviewFileMapper fileMapper, TaskCheckItemApplicationService checkItemApplicationService, TaskFlowMapper flowMapper,
                                      OperationAuditMapper auditMapper, OutboxEventMapper outboxEventMapper,
                                      TaskArchiveApplicationService taskArchiveApplicationService,
                                      ReviewerAssignmentService reviewerAssignmentService, FileApplicationService fileApplicationService) {
        this.taskMapper = taskMapper;
        this.reviewerMapper = reviewerMapper;
        this.opinionMapper = opinionMapper;
        this.fileMapper = fileMapper;
        this.checkItemApplicationService = checkItemApplicationService;
        this.flowMapper = flowMapper;
        this.auditMapper = auditMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.taskArchiveApplicationService = taskArchiveApplicationService;
        this.reviewerAssignmentService = reviewerAssignmentService;
        this.fileApplicationService = fileApplicationService;
    }

    @Transactional
    public WorkflowView transition(long taskId, WorkflowAction action, String comment, CurrentUser currentUser) {
        return transition(taskId, new TransitionCommand(action, comment, null, List.of(), List.of()), currentUser);
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
        validatePayload(action, command);
        List<FileApplicationService.FileView> registeredFiles = registerStageFiles(taskId, command.stageFiles(), currentUser);
        List<ReviewerAssignmentService.ReviewerView> assignedReviewers = assignReviewers(taskId, command, currentUser);
        TaskStatus target = targetStatus(task, action, currentUser);
        if (action == WorkflowAction.START_PCB_OPTIONAL_REVIEW) {
            validatePcbOptionalReviewPrerequisites(task);
        }
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
                new FileApplicationService.FileReferenceCommand(file.companyFileId(), file.fileName(), file.fileSize(), file.md5(), null),
                file.fileKind(), currentUser)).toList();
    }

    private void validatePayload(WorkflowAction action, TransitionCommand command) {
        boolean requiresAssignment = action == WorkflowAction.START_PCB_MUTUAL_REVIEW
                || action == WorkflowAction.START_SCHEMATIC_MUTUAL_REVIEW
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
        if (!command.stageFiles().isEmpty() && action != WorkflowAction.START_PCB_OPTIONAL_REVIEW) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "仅开启工艺/结构评审时可以随流程登记阶段文件");
        }
        if (command.stageFiles().stream().anyMatch(file -> file.scene() != FileUploadScene.PROCESS_REVIEW)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "开启工艺/结构评审时仅允许登记 PROCESS_REVIEW 场景文件");
        }
    }

    private boolean isExpectedAssignmentRole(WorkflowAction action, ReviewRole role) {
        return switch (action) {
            case START_PCB_MUTUAL_REVIEW -> role == ReviewRole.PCB_MUTUAL_CHECK;
            case START_SCHEMATIC_MUTUAL_REVIEW -> role == ReviewRole.SCHEMATIC_MUTUAL_CHECK;
            case START_SCHEMATIC_EXPERT_REVIEW -> role == ReviewRole.SCHEMATIC_HARDWARE_EXPERT || role == ReviewRole.SCHEMATIC_OTHER_EXPERT;
            default -> false;
        };
    }

    private TaskStatus targetStatus(ReviewTaskRecord task, WorkflowAction action, CurrentUser currentUser) {
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        return switch (action) {
            case START_PCB_EXPERT_REVIEW -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.PCB_PENDING_REVIEW,
                    TaskStatus.PCB_EXPERT_REVIEWING, currentUser, Permission.ASSIGN_PCB_EXPERT);
            case ENTER_PCB_DESIGNER_REPLY -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.PCB_EXPERT_REVIEWING,
                    TaskStatus.PCB_DESIGNER_REPLYING, currentUser, Permission.ASSIGN_PCB_EXPERT);
            case START_PCB_OPTIONAL_REVIEW -> requirePcbDesignerOrPermission(task, reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_DESIGNER_REPLYING || current == TaskStatus.PCB_EXPERT_REVIEWING),
                    TaskStatus.PCB_OPTIONAL_REVIEWING, currentUser, Permission.ASSIGN_PROCESS_EXPERT);
            case ENTER_PCB_OPTIONAL_DESIGNER_REPLY -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.PCB_OPTIONAL_REVIEWING,
                    TaskStatus.PCB_OPTIONAL_DESIGNER_REPLYING, currentUser, Permission.ASSIGN_PROCESS_EXPERT);
            case PREPARE_PCB_MUTUAL_ASSIGNMENT -> requireTransition(reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_EXPERT_REVIEWING || current == TaskStatus.PCB_OPTIONAL_REVIEWING
                            || current == TaskStatus.PCB_OPTIONAL_DESIGNER_REPLYING),
                    TaskStatus.PENDING_MUTUAL_ASSIGNMENT, currentUser, Permission.MANAGE_MUTUAL_CHECK);
            case START_PCB_MUTUAL_REVIEW -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.PENDING_MUTUAL_ASSIGNMENT,
                    TaskStatus.MUTUAL_REVIEWING, currentUser, Permission.ASSIGN_PCB_MUTUAL_CHECK);
            case ENTER_PCB_MUTUAL_DESIGNER_REPLY -> requireTransition(reviewType == ReviewType.PCB && current == TaskStatus.MUTUAL_REVIEWING,
                    TaskStatus.PCB_MUTUAL_DESIGNER_REPLYING, currentUser, Permission.MANAGE_MUTUAL_CHECK);
            case START_SCHEMATIC_MUTUAL_REVIEW -> requireTransition(reviewType == ReviewType.SCHEMATIC
                            && (current == TaskStatus.SCHEMATIC_PENDING_LEADER_ASSIGNMENT || current == TaskStatus.SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT),
                    TaskStatus.MUTUAL_REVIEWING, currentUser, Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK);
            case ENTER_SCHEMATIC_MUTUAL_DESIGNER_REPLY -> requireTransition(reviewType == ReviewType.SCHEMATIC && current == TaskStatus.MUTUAL_REVIEWING,
                    TaskStatus.SCHEMATIC_MUTUAL_DESIGNER_REPLYING, currentUser, Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK);
            case PREPARE_SCHEMATIC_EXPERT_ASSIGNMENT -> requireTransition(reviewType == ReviewType.SCHEMATIC
                            && (current == TaskStatus.MUTUAL_REVIEWING || current == TaskStatus.SCHEMATIC_MUTUAL_DESIGNER_REPLYING),
                    TaskStatus.SCHEMATIC_PENDING_REVIEW, currentUser, Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT);
            case START_SCHEMATIC_EXPERT_REVIEW -> requireTransition(reviewType == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_PENDING_REVIEW,
                    TaskStatus.HARDWARE_REVIEWING, currentUser, Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT);
            case ENTER_SCHEMATIC_DESIGNER_REPLY -> requireTransition(reviewType == ReviewType.SCHEMATIC && current == TaskStatus.HARDWARE_REVIEWING,
                    TaskStatus.SCHEMATIC_DESIGNER_REPLYING, currentUser, Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT);
            case REQUEST_FINISH -> requireTransition(current == TaskStatus.MUTUAL_REVIEWING || current == TaskStatus.HARDWARE_REVIEWING
                            || current == TaskStatus.PCB_OPTIONAL_REVIEWING || current == TaskStatus.PCB_MUTUAL_DESIGNER_REPLYING
                            || current == TaskStatus.SCHEMATIC_DESIGNER_REPLYING,
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

    private TaskStatus requirePcbDesignerOrPermission(ReviewTaskRecord task, boolean allowedStatus, TaskStatus target,
                                                       CurrentUser currentUser, Permission permission) {
        if (!allowedStatus) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许执行该流程动作");
        }
        boolean taskDesigner = task.getDesignerId().equals(currentUser.id()) && currentUser.roles().contains(Role.DESIGNER);
        if (!taskDesigner && !permissionPolicy.has(currentUser.roles(), permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者或具有阶段分配权限的人员可以开启工艺/结构评审");
        }
        return target;
    }

    private void validatePcbOptionalReviewPrerequisites(ReviewTaskRecord task) {
        boolean allPreviousOpinionsPassed = opinionMapper.findByTaskId(task.getId()).stream().allMatch(opinion ->
                OpinionStatus.CONFIRMED_PASS.name().equals(opinion.getStatus()) || OpinionStatus.WITHDRAWN.name().equals(opinion.getStatus()));
        if (!allPreviousOpinionsPassed) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "前序评审意见尚未全部确认通过，不能开启工艺/结构评审");
        }
        List<String> roles = task.getReviewRoles() == null || task.getReviewRoles().isBlank() ? List.of()
                : List.of(task.getReviewRoles().split(","));
        if ((roles.contains("PROCESS_EXPERT") || roles.contains("STRUCTURE_EXPERT"))
                && fileMapper.findLatestByTaskIdAndCategory(task.getId(), FileCategory.PROCESS.name()).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已选择工艺或结构评审，请先上传工艺/结构图文件");
        }
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

    public record StageFileCommand(FileUploadScene scene, String companyFileId, String fileName, long fileSize, String md5, String fileKind) {
        public StageFileCommand(FileUploadScene scene, String companyFileId, String fileName, long fileSize, String md5) {
            this(scene, companyFileId, fileName, fileSize, md5, null);
        }
        public StageFileCommand {
            if (scene == null || companyFileId == null || companyFileId.isBlank() || fileName == null || fileName.isBlank() || fileSize < 0) {
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
}
