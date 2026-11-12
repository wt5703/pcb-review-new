package com.bms.workflow.application;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.application.FileApplicationService;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.domain.FileCategory;
import com.bms.identity.domain.SystemRole;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import com.bms.notification.application.ReviewMailNotificationApplicationService;
import com.bms.review.application.TaskCheckItemApplicationService;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.OpinionStatus;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.domain.TaskStatus;
import com.bms.task.domain.TaskReviewerAssignmentCodec;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.domain.WorkflowAction;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 编排 PCB 和原理图评审任务的阶段推进与最终结束，在同一事务内按流程动作校验对应意见来源、校验最新文件、冻结检查项快照并记录流程审计事件。
 */
@Service
public class WorkflowApplicationService {
    private static final List<TaskStatus> PCB_FLOW = List.of(TaskStatus.PENDING_INITIATION,
            TaskStatus.PCB_EXPERT_REVIEWING, TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING,
            TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT, TaskStatus.MUTUAL_CHECK_REVIEWING, TaskStatus.PENDING_FINISH);
    private static final List<TaskStatus> SCHEMATIC_FLOW = List.of(TaskStatus.PENDING_INITIATION,
            TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT, TaskStatus.MUTUAL_CHECK_REVIEWING,
            TaskStatus.SCHEMATIC_EXPERT_PENDING_ASSIGNMENT, TaskStatus.SCHEMATIC_REVIEWING, TaskStatus.PENDING_FINISH);
    private static final Map<WorkflowAction, FileCategory> PCB_STAGE_FILE_CATEGORIES = Map.of(
            WorkflowAction.START_PCB_STRUCTURE_REVIEW, FileCategory.PCB_STRUCTURE_REVIEW,
            WorkflowAction.START_PCB_PROCESS_REVIEW, FileCategory.PCB_PROCESS_REVIEW);
    private static final Set<ReviewRole> SCHEMATIC_MANAGER_ASSIGNED_ROLES =
            Set.of(ReviewRole.HARDWARE_EXPERT);
    private final ReviewTaskMapper taskMapper;
    private final ReviewOpinionMapper opinionMapper;
    private final ReviewFileMapper fileMapper;
    private final FileApplicationService fileApplicationService;
    private final TaskCheckItemApplicationService checkItemApplicationService;
    private final TaskFlowMapper flowMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final TaskArchiveApplicationService taskArchiveApplicationService;
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService;
    private final ReviewMailNotificationApplicationService reviewMailNotificationApplicationService;

    /**
     * 注入任务、意见、文件和日志持久化组件，以及互检、归档、白名单与邮件服务。
     * 流程服务协调各模块完成一次状态变更。
     */
    public WorkflowApplicationService(ReviewTaskMapper taskMapper, ReviewOpinionMapper opinionMapper,
                                      ReviewFileMapper fileMapper, TaskCheckItemApplicationService checkItemApplicationService, TaskFlowMapper flowMapper,
                                      OutboxEventMapper outboxEventMapper,
                                      TaskArchiveApplicationService taskArchiveApplicationService,
                                      ReviewerWhitelistApplicationService reviewerWhitelistApplicationService,
                                      ReviewMailNotificationApplicationService reviewMailNotificationApplicationService,
                                      FileApplicationService fileApplicationService) {
        this.taskMapper = taskMapper;
        this.opinionMapper = opinionMapper;
        this.fileMapper = fileMapper;
        this.fileApplicationService = fileApplicationService;
        this.checkItemApplicationService = checkItemApplicationService;
        this.flowMapper = flowMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.taskArchiveApplicationService = taskArchiveApplicationService;
        this.reviewerWhitelistApplicationService = reviewerWhitelistApplicationService;
        this.reviewMailNotificationApplicationService = reviewMailNotificationApplicationService;
    }

    /** 返回流程分配使用的专家白名单；原理图任务由硬件部开发经理在互检结束后分配硬件专家。 */
    public List<ReviewerWhitelistApplicationService.AssignableReviewerView> listAssignableReviewers(ReviewType reviewType) {
        List<ReviewerWhitelistRole> roles = reviewType == ReviewType.PCB
                ? List.of(ReviewerWhitelistRole.EMC_EXPERT, ReviewerWhitelistRole.PCB_EXPERT)
                : List.of(ReviewerWhitelistRole.HARDWARE_EXPERT);
        return reviewerWhitelistApplicationService.listAssignableUsers(roles);
    }

    /** 互检单分配候选人与任务创建专家候选人职责不同。 */
    public List<ReviewerWhitelistApplicationService.AssignableReviewerView> listMutualCheckReviewers(ReviewType reviewType) {
        ReviewerWhitelistRole role = reviewType == ReviewType.PCB
                ? ReviewerWhitelistRole.PCB_MUTUAL_CHECK : ReviewerWhitelistRole.SCHEMATIC_MUTUAL_CHECK;
        return reviewerWhitelistApplicationService.listAssignableUsers(List.of(role));
    }

    /**
     * 执行不附带人员或文件的单个动作。
     * comment 用于流转日志，operatorEmployeeNo 用于校验权限和记录操作人。
     */
    @Transactional
    public WorkflowView transition(long taskId, WorkflowAction action, String comment, String operatorEmployeeNo) {
        return transition(taskId, new TransitionCommand(action, comment, List.of()), operatorEmployeeNo);
    }

    /**
     * 处理没有随请求上传文件的批量流转。
     * 沿用带文件入口的事务和通知规则，节点所需文件可预先上传。
     */
    @Transactional
    public WorkflowView transition(long taskId, TransitionBatchCommand command, String operatorEmployeeNo) {
        return transition(taskId, command, List.of(), operatorEmployeeNo);
    }

    /**
     * 任务入口先锁定任务并校验附件，再上传文件、执行动作和登记通知。
     * 随请求上传终版时，先检查任务状态、操作者权限和前序意见，以免不满足流转条件时仍调用资源服务。
     */
    @Transactional
    public WorkflowView transition(long taskId, TransitionBatchCommand command, List<TransitionFileCommand> files,
                                   String operatorEmployeeNo) {
        // 同一任务的流转请求串行执行。附件校验先于外部上传。
        taskMapper.lockById(taskId);
        // 附件可省略。省略时按空列表处理，传入时复制列表，固定本次请求中的附件。
        List<TransitionFileCommand> transitionFiles = files == null ? List.of() : List.copyOf(files);
        // 校验附件类别与动作对应，并检查有无重复类别或空文件。未随请求上传时，节点会在流转阶段检查已有文件。
        validateTransitionFiles(taskId, command, transitionFiles);
        // 附件校验通过后仍需确认命令存在，覆盖未随请求上传附件的情况。
        if (command == null) {
            // 命令缺失属于参数错误，本次请求在上传或推进前结束。
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
        }

        // 保留请求中的动作列表，用于判断执行路径并确定通知涉及哪些评审。
        List<WorkflowAction> actions = command.actions();
        // 读取流转说明，双动作时两条日志使用同一份说明。
        String comment = command.comment();
        // 单动作沿用请求携带的按工号、按角色分配信息。多动作留给下方工艺和结构组合分支。
        TransitionCommand singleCommand = actions.size() == 1
                ? new TransitionCommand(actions.get(0), comment, command.reviewerEmployeeNos(), command.reviewerAssignments())
                : null;

        // 随请求上传终版时，先检查是否允许进入待结束，避免失败时仍调用资源服务。
        if (!transitionFiles.isEmpty() && singleCommand != null && singleCommand.action() == WorkflowAction.PENDING_FINISH) {
            // 读取已锁定的任务记录，供下面的前置校验使用。
            ReviewTaskRecord task = requireTask(taskId);
            // 检查任务是否已经结束或终止。
            requireTaskCanTransition(task, WorkflowAction.PENDING_FINISH);
            // 校验待结束动作允许的参数，例如该动作不接受人员分配。
            validatePayload(task, WorkflowAction.PENDING_FINISH, singleCommand);
            // 预先检查设计者权限、当前评审阶段和意见闭环条件。此处仅计算计划，正式执行在上传之后。
            resolveTransitionPlan(task, WorkflowAction.PENDING_FINISH, operatorEmployeeNo);
        }

        // 按请求顺序处理随附文件，没有附件时直接进入动作执行。
        for (TransitionFileCommand file : transitionFiles) {
            // 上传并登记对应类别的最新文件，供后续节点校验。
            fileApplicationService.upload(taskId, file.fileCategory(), file.file(), operatorEmployeeNo);
        }
        WorkflowView result;
        if (singleCommand != null) {
            // 执行节点校验、状态和待办更新及日志写入，保留执行结果中的当前处理人。
            result = executeTransition(taskId, singleCommand, operatorEmployeeNo);
        } else {
            // 多动作必须恰好包含一次结构和一次工艺，重复动作或其他组合均不允许。
            if (actions.size() != PCB_STAGE_FILE_CATEGORIES.size() || !actions.containsAll(PCB_STAGE_FILE_CATEGORIES.keySet())) {
                // 给出接口支持的动作组合。
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "一次只能提交一个流程动作；仅 PCB 工艺评审和结构评审允许同时提交");
            }
            // 两个动作固定先结构、后工艺。响应覆盖整批流转的起止状态。先开启结构评审，并保留整批操作的初始状态。
            WorkflowView structure = executeTransition(taskId,
                    new TransitionCommand(WorkflowAction.START_PCB_STRUCTURE_REVIEW, comment, List.of()), operatorEmployeeNo);
            // 再开启工艺评审。此时任务已进入工艺和结构阶段，会保留刚开启的结构待办。
            WorkflowView process = executeTransition(taskId,
                    new TransitionCommand(WorkflowAction.START_PCB_PROCESS_REVIEW, comment, List.of()), operatorEmployeeNo);
            // 用结构动作的起始状态和工艺动作的结束状态组装整批响应。处理人列表沿用双动作接口的空列表约定。
            result = new WorkflowView(taskId, structure.fromStatus(), process.toStatus(), List.of());
        }

        // 所有动作都执行成功后，再统一登记工艺或结构评审通知。
        notifyPcbStageReviewStarted(taskId, actions);
        return result;
    }

    /**
     * 检查随附文件与动作一一对应。
     * 待结束按任务类型校验终版，工艺和结构按对应动作校验。
     * 重复类别、缺少预期类别或空文件均会被拒绝。
     * 没有随附文件时，由后续节点校验已上传文件。
     */
    private void validateTransitionFiles(long taskId, TransitionBatchCommand command, List<TransitionFileCommand> files) {
        if (files.isEmpty()) {
            return;
        }
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "上传流程文件时必须提供流程动作");
        }
        Set<FileCategory> expectedCategories = command.actions().equals(List.of(WorkflowAction.PENDING_FINISH))
                ? Set.of(finalFileCategory(requireTask(taskId)))
                : command.actions().stream().map(this::stageFileCategoryFor).collect(java.util.stream.Collectors.toSet());
        if (expectedCategories.size() != command.actions().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能重复，且仅开启工艺/结构评审或进入待结束时支持上传文件");
        }
        Set<FileCategory> providedCategories = files.stream().map(TransitionFileCommand::fileCategory)
                .collect(java.util.stream.Collectors.toSet());
        if (providedCategories.size() != files.size() || !providedCategories.equals(expectedCategories)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程文件类型必须与当前动作和任务类型对应");
        }
        if (files.stream().anyMatch(file -> file.file() == null || file.file().isEmpty())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程文件不能为空");
        }
    }

    /**
     * 按统一映射查找工艺或结构评审所需文件类别。
     * 动作没有配置附件类别时，拒绝随该动作上传。
     */
    private FileCategory stageFileCategoryFor(WorkflowAction action) {
        FileCategory category = PCB_STAGE_FILE_CATEGORIES.get(action);
        if (category == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持上传文件");
        }
        return category;
    }

    /** 通知本次实际开启的工艺或结构评审人员。 */
    private void notifyPcbStageReviewStarted(long taskId, List<WorkflowAction> actions) {
        List<FileCategory> fileCategories = actions.stream()
                .filter(PCB_STAGE_FILE_CATEGORIES::containsKey)
                .map(PCB_STAGE_FILE_CATEGORIES::get)
                .toList();
        if (!fileCategories.isEmpty()) {
            reviewMailNotificationApplicationService.enqueuePcbStageReview(requireTask(taskId), fileCategories);
        }
    }

    /**
     * 在事务内锁定任务后执行单动作，避免同一任务的并发流转都基于旧状态写入。
     */
    @Transactional
    public WorkflowView transition(long taskId, TransitionCommand command, String operatorEmployeeNo) {
        taskMapper.lockById(taskId);
        return executeTransition(taskId, command, operatorEmployeeNo);
    }

    /**
     * 依次准备流转上下文、持久化任务和审计数据、执行归档或通知。
     * 单动作和批量入口均可复用，调用方负责事务和行锁。
     */
    private WorkflowView executeTransition(long taskId, TransitionCommand command, String operatorEmployeeNo) {
        TransitionContext context = prepareTransition(taskId, command, operatorEmployeeNo);
        persistTransition(context);
        executePostTransition(context);
        return context.toView();
    }

    /**
     * 设计者撤回本人尚未产生实际评审意见的任务。
     * PCB 须处于专家评审，原理图须处于互检单待分配。
     * 撤回后进入待发起、清空待办并记录原因，无意见确认记录不计为实际意见。
     */
    @Transactional
    public WorkflowView withdraw(long taskId, String reason, String operatorEmployeeNo) {
        ReviewTaskRecord task = requireTaskForControl(taskId, reason);
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        requireDesignerTransition(task, status.canWithdraw(ReviewType.valueOf(task.getReviewType()), false),
                TaskStatus.PENDING_INITIATION, operatorEmployeeNo);
        if (opinionMapper.findByTaskId(taskId).stream().anyMatch(opinion -> !opinion.isNoOpinion())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "任务已产生评审意见，不能撤回");
        }
        return persistTaskControl(task, WorkflowAction.WITHDRAW, TaskStatus.PENDING_INITIATION, reason, operatorEmployeeNo, List.of());
    }

    /**
     * 校验本人身份、任务状态和原因后终止任务。
     * 终止会清空当前待办，并在流转日志中记录操作人及原因。
     */
    @Transactional
    public WorkflowView terminate(long taskId, String reason, String operatorEmployeeNo) {
        ReviewTaskRecord task = requireTaskForControl(taskId, reason);
        requireDesignerTransition(task, true, TaskStatus.TERMINATED, operatorEmployeeNo);
        return persistTaskControl(task, WorkflowAction.TERMINATE, TaskStatus.TERMINATED, reason, operatorEmployeeNo, List.of());
    }

    /**
     * 校验原因、操作者权限和目标状态后，将任务退回同一流程的任意前序节点。
     * 根据目标节点重建待办，保留原有文件、意见和历史日志。
     */
    @Transactional
    public WorkflowView returnTo(long taskId, TaskStatus target, String reason, String operatorEmployeeNo) {
        ReviewTaskRecord task = requireTaskForControl(taskId, reason);
        requireReturnPermission(task, operatorEmployeeNo);
        if (target == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择退回节点");
        }
        if (!returnTargets(task).contains(target)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "仅可退回当前评审流程中的前序节点");
        }
        return persistTaskControl(task, WorkflowAction.RETURN, target, reason, operatorEmployeeNo, reviewersForReturn(task, target));
    }

    /**
     * 按流程先后返回当前操作者可以选择的退回目标。
     * 查询和执行退回复用同一套权限及节点规则。
     */
    public List<TaskStatus> listReturnTargets(long taskId, String operatorEmployeeNo) {
        ReviewTaskRecord task = requireTask(taskId);
        requireReturnPermission(task, operatorEmployeeNo);
        return returnTargets(task);
    }

    /**
     * 按任务类型选择流程，从起点截取当前节点之前的状态。
     * 当前节点没有前序状态或不在流程中时返回空列表。
     */
    private List<TaskStatus> returnTargets(ReviewTaskRecord task) {
        List<TaskStatus> flow = ReviewType.PCB.name().equals(task.getReviewType()) ? PCB_FLOW : SCHEMATIC_FLOW;
        return flow.subList(0, Math.max(0, flow.indexOf(TaskStatus.valueOf(task.getStatus()))));
    }

    /**
     * 撤回、终止和退回的公共前置校验。
     * 原因必填且最多 2000 字符，校验后锁定并读取任务，拒绝继续操作已结束或已终止的任务。
     */
    private ReviewTaskRecord requireTaskForControl(long taskId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "操作原因必填且不能超过 2000 字符");
        }
        taskMapper.lockById(taskId);
        ReviewTaskRecord task = requireTask(taskId);
        if (TaskStatus.isClosed(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束或已终止的任务不允许流转");
        }
        return task;
    }

    /**
     * 校验操作人能否退回。
     * 任务已分配的专家、互检人员、当前处理人，以及硬件部开发经理、原理图组长和 PCB 组长可以操作。
     */
    private void requireReturnPermission(ReviewTaskRecord task, String operatorEmployeeNo) {
        boolean assignedReviewer = TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments()).stream()
                .anyMatch(assignment -> assignment.reviewerEmployeeNos().contains(operatorEmployeeNo))
                || TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getMutualCheckReviewerEmployeeNos()).contains(operatorEmployeeNo)
                || TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getAssignedReviewerEmployeeNos()).contains(operatorEmployeeNo);
        if (!assignedReviewer
                && !reviewerWhitelistApplicationService.hasSystemRole(operatorEmployeeNo, SystemRole.HARDWARE_MANAGER)
                && !reviewerWhitelistApplicationService.hasSystemRole(operatorEmployeeNo, SystemRole.SCHEMATIC_LEADER)
                && !reviewerWhitelistApplicationService.hasSystemRole(operatorEmployeeNo, SystemRole.PCB_LEADER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务评审专家、硬件部开发经理、原理图组长或 PCB 组长可以退回任务");
        }
    }

    /**
     * 为已校验的撤回、终止或退回构建流转上下文，复用任务状态、待办、日志和事件的写入过程。
     */
    private WorkflowView persistTaskControl(ReviewTaskRecord task, WorkflowAction action, TaskStatus target,
                                            String reason, String operatorEmployeeNo, List<String> reviewerEmployeeNos) {
        TransitionContext context = new TransitionContext(task, action, reason, operatorEmployeeNo,
                TaskStatus.valueOf(task.getStatus()), new TransitionPlan(target, false), reviewerEmployeeNos, List.of());
        persistTransition(context);
        return context.toView();
    }

    /**
     * 按目标节点恢复待办。
     * 专家沿用角色分配，互检沿用分配快照，工艺或结构以已开启分支为准。
     * 分配节点本身不在这里写入评审人。
     */
    private List<String> reviewersForReturn(ReviewTaskRecord task, TaskStatus target) {
        return switch (target) {
            case PCB_EXPERT_REVIEWING, SCHEMATIC_REVIEWING ->
                    reviewerEmployeeNosForRoles(task, ReviewRole.PCB_EXPERT, ReviewRole.EMC_EXPERT, ReviewRole.HARDWARE_EXPERT);
            case PCB_PROCESS_STRUCTURE_REVIEWING -> {
                List<ReviewRole> roles = startedOptionalReviewRoles(task.getId());
                yield roles.isEmpty() ? List.of() : reviewerEmployeeNosForRoles(task, roles.toArray(ReviewRole[]::new));
            }
            case MUTUAL_CHECK_REVIEWING ->
                    TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getMutualCheckReviewerEmployeeNos());
            default -> List.of();
        };
    }

    /**
     * 从流转日志识别曾开启的工艺和结构分支，去重后返回对应角色，供退回该阶段时恢复专家。
     */
    private List<ReviewRole> startedOptionalReviewRoles(long taskId) {
        Set<ReviewRole> roles = new LinkedHashSet<>();
        for (TaskFlowRecord record : flowMapper.findByTaskId(taskId)) {
            if (WorkflowAction.START_PCB_PROCESS_REVIEW.name().equals(record.getAction())) {
                roles.add(ReviewRole.PROCESS_EXPERT);
            } else if (WorkflowAction.START_PCB_STRUCTURE_REVIEW.name().equals(record.getAction())) {
                roles.add(ReviewRole.STRUCTURE_EXPERT);
            }
        }
        return List.copyOf(roles);
    }

    /**
     * 读取任务，校验参数、权限、前序意见及附件，并计算目标状态。
     * 正式结束前生成或保留检查项快照，未收齐确认时将 FINISH 记录为 FINISH_CONFIRM。
     */
    private TransitionContext prepareTransition(long taskId, TransitionCommand command, String operatorEmployeeNo) {
        requireTransitionCommand(command);
        ReviewTaskRecord task = requireTask(taskId);
        WorkflowAction action = command.action();
        requireTaskCanTransition(task, action);
        validatePayload(task, action, command);

        // 先确认当前用户、当前状态和前序意见允许执行该动作，再确定最终状态。
        TransitionPlan plan = resolveTransitionPlan(task, action, operatorEmployeeNo);
        // 文件在文件域上传完成；流程域只校验本次节点所需的最新文件是否已存在。
        validateStageFilePresence(task, action);
        // 结束前需要冻结检查项快照，且必须发生在任务状态变为 FINISHED 之前。
        freezeCheckItemsBeforeFinish(task, plan);
        WorkflowAction loggedAction = action == WorkflowAction.FINISH && !plan.finishing()
                ? WorkflowAction.FINISH_CONFIRM : action;
        return new TransitionContext(task, loggedAction, command.comment(), operatorEmployeeNo,
                TaskStatus.valueOf(task.getStatus()), plan, command.reviewerEmployeeNos(), command.reviewerAssignments());
    }

    /** 在同一事务内更新任务状态和当前处理人，并写入流转日志及状态变更事件。 */
    private void persistTransition(TransitionContext context) {
        replaceSchematicManagerAssignments(context);
        refreshActiveReviewers(context);
        persistStatus(context.task(), context.plan().targetStatus());
        persistActiveReviewers(context.task());
        appendHistory(context);
        publishStatusChangedEvent(context.task().getId(), context.action(), context.plan().targetStatus());
    }

    /**
     * 原理图任务创建时已指定 EMC 专家；经理分配时仅替换硬件专家，保留 EMC 及其他角色的既有分配。
     */
    private void replaceSchematicManagerAssignments(TransitionContext context) {
        if (context.action() != WorkflowAction.START_SCHEMATIC_EXPERT_REVIEW) {
            return;
        }
        Map<ReviewRole, TaskReviewerAssignment> assignmentsByRole = new LinkedHashMap<>();
        TaskReviewerAssignmentCodec.decode(context.task().getReviewerAssignments()).stream()
                .filter(assignment -> !SCHEMATIC_MANAGER_ASSIGNED_ROLES.contains(assignment.reviewRole()))
                .forEach(assignment -> assignmentsByRole.put(assignment.reviewRole(), assignment));
        context.reviewerAssignments().forEach(assignment -> assignmentsByRole.put(assignment.reviewRole(), assignment));
        context.task().setReviewerAssignments(TaskReviewerAssignmentCodec.encode(List.copyOf(assignmentsByRole.values())));
    }

    /**
     * assigned_reviewer_employee_nos 只保存当前已开启节点的待处理人员：
     * - PCB 首轮仅 PCB、EMC 专家，工艺/结构在各自节点开启时追加；
     * - 互检使用本次分配的人员；原理图 EMC 专家在创建时分配，硬件专家由硬件部开发经理在互检结束后分配；
     * - 已通过前一节点校验的历史人员在进入下一节点时清理，兼容旧数据中曾预写全部人员的情况。
     */
    private void refreshActiveReviewers(TransitionContext context) {
        ReviewTaskRecord task = context.task();
        Set<String> activeReviewerEmployeeNos = new LinkedHashSet<>(TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getAssignedReviewerEmployeeNos()));
        switch (context.action()) {
            case START_PCB_PROCESS_REVIEW, START_PCB_STRUCTURE_REVIEW -> {
                // 首次进入工艺/结构阶段时，前序 PCB、EMC 待办已全部闭环，直接重建当前阶段人员。
                // 后续再开启另一类评审时必须保留已开启分支的人员：同一人可能同时是 PCB/EMC 与
                // 工艺/结构专家，按工号删除前序角色人员会误删仍在进行中的结构或工艺待办。
                if (context.fromStatus() == TaskStatus.PCB_EXPERT_REVIEWING) {
                    activeReviewerEmployeeNos.clear();
                }
                activeReviewerEmployeeNos.addAll(context.action() == WorkflowAction.START_PCB_PROCESS_REVIEW
                        ? reviewerEmployeeNosForRoles(task, ReviewRole.PROCESS_EXPERT)
                        : reviewerEmployeeNosForRoles(task, ReviewRole.STRUCTURE_EXPERT));
            }
            case START_PCB_MATUAL_REVIEW, START_SCHEMATIC_MATUAL_REVIEW -> {
                // 互检人员以本次分配为准，且前序阶段意见已在流转校验中闭环。
                activeReviewerEmployeeNos.clear();
                activeReviewerEmployeeNos.addAll(context.reviewerEmployeeNos());
                // 单独保留互检单分配快照，避免人员完成待办后从 active 字段移除，任务列表无法追溯实际分配人。
                task.setMutualCheckReviewerEmployeeNos(TaskReviewerAssignmentCodec
                        .encodeReviewerEmployeeNos(context.reviewerEmployeeNos()));
            }
            case START_PCB_MATUAL_ASSIGNMENT -> activeReviewerEmployeeNos.clear();
            case START_SCHEMATIC_EXPERT_REVIEW -> {
                // 原理图互检意见已闭环，切换到硬件部开发经理刚刚指定的原理图评审专家。
                activeReviewerEmployeeNos.clear();
                activeReviewerEmployeeNos.addAll(reviewerEmployeeNosForRoles(task, ReviewRole.HARDWARE_EXPERT,
                        ReviewRole.EMC_EXPERT, ReviewRole.PCB_EXPERT));
            }
            case START_SCHEMATIC_EXPERT_ASSIGNMENT -> {
                // 原理图互检结束后，任务进入硬件专家分配；具备 HARDWARE-MANAGER 角色的人员可处理该待办。
                activeReviewerEmployeeNos.clear();
            }
            case PENDING_FINISH -> {
                activeReviewerEmployeeNos.clear();
                activeReviewerEmployeeNos.addAll(finishApprovers(task).values().stream().flatMap(List::stream).toList());
            }
            case FINISH_CONFIRM -> {
                activeReviewerEmployeeNos.clear();
                activeReviewerEmployeeNos.addAll(context.plan().pendingApprovers());
            }
            case FINISH, WITHDRAW, TERMINATE -> activeReviewerEmployeeNos.clear();
            case RETURN -> {
                activeReviewerEmployeeNos.clear();
                activeReviewerEmployeeNos.addAll(context.reviewerEmployeeNos());
            }
            default -> { }
        }
        task.setAssignedReviewerEmployeeNos(TaskReviewerAssignmentCodec.encodeReviewerEmployeeNos(activeReviewerEmployeeNos));
    }

    /**
     * 从角色分配快照提取指定评审角色的工号并去重。
     * 没有匹配工号且任务有组长时，回退到组长工号以兼容存量数据。
     */
    private List<String> reviewerEmployeeNosForRoles(ReviewTaskRecord task, ReviewRole... roles) {
        Set<ReviewRole> requiredRoles = Set.of(roles);
        List<String> employeeNos = TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments()).stream()
                .filter(assignment -> requiredRoles.contains(assignment.reviewRole()))
                .flatMap(assignment -> assignment.reviewerEmployeeNos().stream())
                .distinct()
                .toList();
        // 兼容旧任务：没有角色—人员映射时沿用历史组长。
        return employeeNos.isEmpty() && task.getExpertLeaderEmployeeNo() != null ? List.of(task.getExpertLeaderEmployeeNo()) : employeeNos;
    }

    /**
     * 向 Outbox 登记任务 ID、动作和目标状态。
     * 事件和任务状态、流转日志一起落库，供异步消费。
     */
    private void publishStatusChangedEvent(long taskId, WorkflowAction action, TaskStatus targetStatus) {
        outboxEventMapper.insert(new OutboxEventRecord("TASK_STATUS_CHANGED", "REVIEW_TASK", taskId,
                "{\"taskId\":" + taskId + ",\"action\":\"" + action.name() + "\",\"toStatus\":\"" + targetStatus.name() + "\"}", "PENDING"));
    }

    /** 状态落库后登记业务通知：正式结束时归档并登记终版邮件，开启原理图专家评审时登记评审邮件。 */
    private void executePostTransition(TransitionContext context) {
        if (context.plan().finishing()) {
            taskArchiveApplicationService.archive(context.task());
            reviewMailNotificationApplicationService.enqueueTaskFinished(context.task());
        } else if (context.action() == WorkflowAction.START_SCHEMATIC_EXPERT_REVIEW) {
            reviewMailNotificationApplicationService.enqueueSchematicReviewStarted(context.task());
        }
    }

    /**
     * 校验单动作命令及动作字段是否存在，缺少任一项时抛出参数校验异常。
     */
    private void requireTransitionCommand(TransitionCommand command) {
        if (command == null || command.action() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
        }
    }

    /**
     * 检查常规流转入口是否允许当前任务和动作。
     * 已关闭任务禁止推进，创建、撤回、终止和退回须走对应入口，确认结束由调用方传 FINISH。
     */
    private void requireTaskCanTransition(ReviewTaskRecord task, WorkflowAction action) {
        if (TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许重新打开或流转");
        }
        if (TaskStatus.TERMINATED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已终止任务不允许流转");
        }
        if (action == WorkflowAction.FINISH_CONFIRM) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "确认结束请传 FINISH 动作");
        }
        if (action == WorkflowAction.WITHDRAW || action == WorkflowAction.TERMINATE || action == WorkflowAction.RETURN) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "撤回、终止和退回请使用对应的流程接口");
        }
        if (action == WorkflowAction.CREATE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CREATE 只能通过任务提交接口执行，不能调用流程推进接口");
        }
    }

    /**
     * 为当前动作计算流转计划。
     * 结束动作进入双角色确认逻辑，普通动作先校验前序条件，再根据任务类型、当前状态和权限确定目标。
     */
    private TransitionPlan resolveTransitionPlan(ReviewTaskRecord task, WorkflowAction action, String operatorEmployeeNo) {
        if (action == WorkflowAction.FINISH) {
            return resolveFinishPlan(task, operatorEmployeeNo);
        }
        validateActionPrerequisites(task, action);
        return new TransitionPlan(resolveNonFinishTargetStatus(task, action, operatorEmployeeNo), false);
    }

    /**
     * 只统计最近一次进入待结束后的确认记录。
     * 校验本次确认资格及意见闭环，计算剩余待办。
     * 组长和硬件部经理须由不同人员分别确认，全部确认后才进入已结束。
     */
    private TransitionPlan resolveFinishPlan(ReviewTaskRecord task, String operatorEmployeeNo) {
        requireTransition(TaskStatus.PENDING_FINISH.name().equals(task.getStatus()), TaskStatus.FINISHED);
        Map<SystemRole, List<String>> candidates = finishApprovers(task);
        Set<String> confirmedRoles = new LinkedHashSet<>();
        Set<String> confirmedOperators = new LinkedHashSet<>();
        for (TaskFlowRecord record : flowMapper.findByTaskId(task.getId())) {
            if (WorkflowAction.PENDING_FINISH.name().equals(record.getAction())) {
                confirmedRoles.clear();
                confirmedOperators.clear();
            } else if (WorkflowAction.FINISH_CONFIRM.name().equals(record.getAction())) {
                confirmedRoles.add(record.getApprovalRole());
                confirmedOperators.add(record.getOperateEmployeeNo());
            }
        }
        if (confirmedOperators.contains(operatorEmployeeNo)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前用户已确认结束");
        }
        List<SystemRole> eligible = candidates.entrySet().stream()
                .filter(entry -> !confirmedRoles.contains(entry.getKey().code()) && entry.getValue().contains(operatorEmployeeNo))
                .map(Map.Entry::getKey).toList();
        if (eligible.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅当前待确认的组长或硬件部经理可以确认结束");
        }
        requireOpinionsPassed(task.getId(), List.of(), "存在未确认通过的意见，不能结束任务");
        // 同一人兼任两种角色时，优先选择能让另一人完成剩余确认的角色。
        SystemRole role = eligible.stream().filter(option -> candidates.entrySet().stream()
                        .anyMatch(entry -> entry.getKey() != option && !confirmedRoles.contains(entry.getKey().code())
                                && entry.getValue().stream().anyMatch(employeeNo -> !employeeNo.equals(operatorEmployeeNo))))
                .findFirst().orElse(eligible.get(0));
        confirmedRoles.add(role.code());
        confirmedOperators.add(operatorEmployeeNo);
        List<String> pendingApprovers = candidates.entrySet().stream()
                .filter(entry -> !confirmedRoles.contains(entry.getKey().code()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(employeeNo -> !confirmedOperators.contains(employeeNo)).distinct().toList();
        boolean finished = confirmedRoles.size() == candidates.size();
        return new TransitionPlan(finished ? TaskStatus.FINISHED : TaskStatus.PENDING_FINISH,
                finished, role, pendingApprovers);
    }

    /**
     * 按组长和硬件部经理两个角色查询候选人。
     * PCB 组长按白名单角色取得，原理图组长还必须是该任务指定的组长。
     */
    private Map<SystemRole, List<String>> finishApprovers(ReviewTaskRecord task) {
        boolean pcb = ReviewType.PCB.name().equals(task.getReviewType());
        SystemRole leaderRole = pcb ? SystemRole.PCB_LEADER : SystemRole.SCHEMATIC_LEADER;
        List<String> leaders = reviewerWhitelistApplicationService.findSystemRoleEmployeeNos(leaderRole);
        if (!pcb) {
            leaders = leaders.stream().filter(employeeNo -> employeeNo.equals(task.getExpertLeaderEmployeeNo())).toList();
        }
        Map<SystemRole, List<String>> candidates = new LinkedHashMap<>();
        candidates.put(leaderRole, leaders);
        candidates.put(SystemRole.HARDWARE_MANAGER,
                reviewerWhitelistApplicationService.findSystemRoleEmployeeNos(SystemRole.HARDWARE_MANAGER));
        return candidates;
    }

    /** 结束前先冻结任务检查项快照，随后才更新状态并归档。 */
    private void freezeCheckItemsBeforeFinish(ReviewTaskRecord task, TransitionPlan plan) {
        if (plan.finishing()) {
            checkItemApplicationService.materializeActiveTaskItems(task.getId());
        }
    }

    /**
     * 把目标状态写入任务记录并更新数据库。
     * 更新结果不是一行时抛出任务不存在异常，交由外层事务回滚。
     */
    private void persistStatus(ReviewTaskRecord task, TaskStatus targetStatus) {
        task.setStatus(targetStatus.name());
        if (taskMapper.update(task) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
    }

    /** 持久化当前待办工号和互检分配快照，供待办查询和后续流转使用。 */
    private void persistActiveReviewers(ReviewTaskRecord task) {
        if (taskMapper.updateAssignedReviewerEmployeeNos(task) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
    }

    /**
     * 检查动作能否携带人员分配。
     * 互检评审需要至少一名按工号分配的人员，原理图专家评审使用按角色分配，其余动作按各自允许的字段校验。
     */
    private void validatePayload(ReviewTaskRecord task, WorkflowAction action, TransitionCommand command) {
        boolean requiresAssignment = action == WorkflowAction.START_PCB_MATUAL_REVIEW
                || action == WorkflowAction.START_SCHEMATIC_MATUAL_REVIEW;
        if (requiresAssignment && command.reviewerEmployeeNos().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "进入该评审节点时必须指定至少一名评审人员");
        }
        if (!requiresAssignment && !command.reviewerEmployeeNos().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持分配评审人员");
        }
        if (action == WorkflowAction.START_SCHEMATIC_EXPERT_REVIEW) {
            validateSchematicManagerAssignments(task, command.reviewerAssignments());
            return;
        }
        if (!command.reviewerAssignments().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持按评审角色分配人员");
        }
    }

    /**
     * 校验经理提交的硬件评审分配是否与任务配置一致。
     * 每个所需角色只能提交一组人员且至少一人，拒绝额外角色。
     */
    private void validateSchematicManagerAssignments(ReviewTaskRecord task,
                                                      List<TaskReviewerAssignment> assignments) {
        Set<ReviewRole> expectedRoles = parseReviewRoles(task.getReviewRoles()).stream()
                .filter(SCHEMATIC_MANAGER_ASSIGNED_ROLES::contains)
                .collect(java.util.stream.Collectors.toSet());
        if (expectedRoles.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "原理图任务未配置硬件评审角色");
        }
        Map<ReviewRole, List<String>> reviewersByRole = new LinkedHashMap<>();
        for (TaskReviewerAssignment assignment : assignments == null ? List.<TaskReviewerAssignment>of() : assignments) {
            if (!SCHEMATIC_MANAGER_ASSIGNED_ROLES.contains(assignment.reviewRole())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "硬件部开发经理仅可分配硬件评审人员");
            }
            if (reviewersByRole.putIfAbsent(assignment.reviewRole(), assignment.reviewerEmployeeNos()) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "同一评审角色只能分配一组专家");
            }
        }
        if (!reviewersByRole.keySet().equals(expectedRoles) || reviewersByRole.values().stream().anyMatch(List::isEmpty)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请为每个已选择的硬件评审角色至少指定一名专家");
        }
    }

    /** 按动作匹配任务类型和当前状态，并校验操作者权限，再返回目标状态。 */
    private TaskStatus resolveNonFinishTargetStatus(ReviewTaskRecord task, WorkflowAction action, String operatorEmployeeNo) {
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        return switch (action) {
            case CREATE, FINISH, FINISH_CONFIRM, WITHDRAW, TERMINATE, RETURN -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不能按普通节点推进");
            case START_PCB_STRUCTURE_REVIEW -> requireDesignerTransition(task, reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_EXPERT_REVIEWING || current == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING),
                    TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING, operatorEmployeeNo);
            case START_PCB_PROCESS_REVIEW -> requireDesignerTransition(task, reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_EXPERT_REVIEWING || current == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING),
                    TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING, operatorEmployeeNo);
            case START_PCB_MATUAL_ASSIGNMENT -> requireDesignerTransition(task, reviewType == ReviewType.PCB
                            && (current == TaskStatus.PCB_EXPERT_REVIEWING || current == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING),
                    TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT, operatorEmployeeNo);
            case START_PCB_MATUAL_REVIEW -> requireSystemRoleTransition(task,
                    reviewType == ReviewType.PCB && current == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT,
                    TaskStatus.MUTUAL_CHECK_REVIEWING, operatorEmployeeNo, SystemRole.PCB_LEADER,
                    "仅具备 PCB-LEADER 角色的用户可以分配 PCB 互检单评审人员");
            case START_SCHEMATIC_MATUAL_REVIEW -> requireSchematicLeaderTransition(task,
                    reviewType == ReviewType.SCHEMATIC && current == TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT,
                    TaskStatus.MUTUAL_CHECK_REVIEWING, operatorEmployeeNo);
            case START_SCHEMATIC_EXPERT_ASSIGNMENT -> requireDesignerTransition(task,
                    reviewType == ReviewType.SCHEMATIC && current == TaskStatus.MUTUAL_CHECK_REVIEWING,
                    TaskStatus.SCHEMATIC_EXPERT_PENDING_ASSIGNMENT, operatorEmployeeNo);
            case START_SCHEMATIC_EXPERT_REVIEW -> requireHardwareManagerTransition(task,
                    reviewType == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_EXPERT_PENDING_ASSIGNMENT,
                    TaskStatus.SCHEMATIC_REVIEWING, operatorEmployeeNo);
            case PENDING_FINISH -> requireDesignerTransition(task,
                    (reviewType == ReviewType.PCB && current == TaskStatus.MUTUAL_CHECK_REVIEWING)
                            || (reviewType == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_REVIEWING),
                    TaskStatus.PENDING_FINISH, operatorEmployeeNo);
        };
    }

    /**
     * 调用方传入当前状态是否满足动作条件。
     * 条件通过则返回目标状态，否则抛出状态冲突。
     */
    private TaskStatus requireTransition(boolean allowedStatus, TaskStatus target) {
        if (!allowedStatus) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许执行该流程动作");
        }
        return target;
    }

    /**
     * 校验节点准入条件和设计者身份。
     * 状态不符返回状态冲突，工号不符返回禁止操作，通过后返回目标状态。
     */
    private TaskStatus requireDesignerTransition(ReviewTaskRecord task, boolean allowedStatus, TaskStatus target,
                                                 String operatorEmployeeNo) {
        if (!allowedStatus) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许执行该流程动作");
        }
        if (!task.getDesignerEmployeeNo().equals(operatorEmployeeNo)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者可以执行当前流程动作");
        }
        return target;
    }

    /**
     * 校验节点准入和指定系统角色。
     * 权限不足时使用调用方提供的提示，全部通过后返回目标状态。
     */
    private TaskStatus requireSystemRoleTransition(ReviewTaskRecord task, boolean allowedStatus, TaskStatus target,
                                                   String operatorEmployeeNo, SystemRole role, String forbiddenMessage) {
        if (!allowedStatus) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许执行该流程动作");
        }
        if (!reviewerWhitelistApplicationService.hasSystemRole(operatorEmployeeNo, role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, forbiddenMessage);
        }
        return target;
    }

    /**
     * 校验原理图互检分配资格。
     * 操作者须同时是任务指定组长，并具有 SCHEMATIC-LEADER 系统角色。
     */
    private TaskStatus requireSchematicLeaderTransition(ReviewTaskRecord task, boolean allowedStatus, TaskStatus target,
                                                        String operatorEmployeeNo) {
        if (!allowedStatus) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许执行该流程动作");
        }
        if (!operatorEmployeeNo.equals(task.getExpertLeaderEmployeeNo())
                || !reviewerWhitelistApplicationService.hasSystemRole(operatorEmployeeNo, SystemRole.SCHEMATIC_LEADER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务指定且具备 SCHEMATIC-LEADER 角色的原理图组长可以分配互检单评审人员");
        }
        return target;
    }

    /** 原理图硬件专家分配任务仅归属于具备 HARDWARE-MANAGER 角色的用户。 */
    private TaskStatus requireHardwareManagerTransition(ReviewTaskRecord task, boolean allowedStatus, TaskStatus target,
                                                        String operatorEmployeeNo) {
        if (!allowedStatus) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许执行该流程动作");
        }
        if (!reviewerWhitelistApplicationService.hasSystemRole(operatorEmployeeNo, SystemRole.HARDWARE_MANAGER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅硬件部开发经理可以分配原理图评审人员");
        }
        return target;
    }

    /**
     * 按动作确定需要检查的前序意见来源，并校验意见闭环及对应评审人是否已确认提交。
     */
    private void validateActionPrerequisites(ReviewTaskRecord task, WorkflowAction action) {
        switch (action) {
            case START_PCB_STRUCTURE_REVIEW, START_PCB_PROCESS_REVIEW -> {
                requireStageCompleted(task, List.of(OpinionSourceType.PCB_REVIEW), pcbReviewSubmissionRequirements(task),
                        "专家评审意见尚未全部确认通过，不能开启工艺或结构评审");
            }
            case START_PCB_MATUAL_ASSIGNMENT -> requireStageCompleted(task,
                    List.of(OpinionSourceType.PCB_PROCESS_REVIEW, OpinionSourceType.PCB_STRUCTURE_REVIEW),
                    pcbOptionalReviewSubmissionRequirements(task), "工艺或结构评审意见尚未全部确认通过，不能开启互检单分配");
            case START_SCHEMATIC_EXPERT_ASSIGNMENT ->
                    requireStageCompleted(task, List.of(OpinionSourceType.MUTUAL_CHECK_ITEM, OpinionSourceType.MUTUAL_EXTRA),
                            mutualCheckSubmissionRequirements(task), "互检单意见尚未全部确认通过，不能开启硬件专家分配");
            case PENDING_FINISH -> {
                if (ReviewType.PCB.name().equals(task.getReviewType())) {
                    requireStageCompleted(task, List.of(OpinionSourceType.MUTUAL_CHECK_ITEM, OpinionSourceType.MUTUAL_EXTRA),
                            mutualCheckSubmissionRequirements(task), "互检单意见尚未全部确认通过，不能进入待结束");
                } else {
                    requireStageCompleted(task, List.of(OpinionSourceType.SCHEMATIC_REVIEW), schematicReviewSubmissionRequirements(task),
                            "原理图专家评审意见尚未全部确认通过，不能进入待结束");
                }
            }
            default -> { }
        }
    }

    /** 流程推进前确认文件接口已完成当前阶段文件的上传和落库。 */
    private void validateStageFilePresence(ReviewTaskRecord task, WorkflowAction action) {
        if (action == WorkflowAction.PENDING_FINISH) {
            requireFinalFile(task);
            return;
        }
        if (action == WorkflowAction.START_PCB_MATUAL_ASSIGNMENT) {
            requireLatestStageFile(task, FileCategory.PCB_PROCESS_REVIEW);
            requireLatestStageFile(task, FileCategory.PCB_STRUCTURE_REVIEW);
            return;
        }
        FileCategory category = switch (action) {
            case START_PCB_PROCESS_REVIEW -> FileCategory.PCB_PROCESS_REVIEW;
            case START_PCB_STRUCTURE_REVIEW -> FileCategory.PCB_STRUCTURE_REVIEW;
            case START_SCHEMATIC_EXPERT_REVIEW -> FileCategory.SCHEMATIC_REVIEW;
            default -> null;
        };
        if (category != null) {
            requireLatestStageFile(task, category);
        }
    }

    /**
     * 按任务类型返回终版类别，PCB 对应 PCB_REVIEW，原理图对应 SCHEMATIC_REVIEW。
     */
    private FileCategory finalFileCategory(ReviewTaskRecord task) {
        return ReviewType.PCB.name().equals(task.getReviewType()) ? FileCategory.PCB_REVIEW : FileCategory.SCHEMATIC_REVIEW;
    }

    /**
     * 要求最新文件中有设计者在当前评审节点上传的终版。
     * 代码也比较终版与意见的更新时间，当前时间比较结果不阻止流转。
     */
    private void requireFinalFile(ReviewTaskRecord task) {
        FileCategory category = finalFileCategory(task);
        var files = fileMapper.findLatestByTaskIdAndCategory(task.getId(), category.name());
        var finalFile = files.stream().filter(file -> task.getStatus().equals(file.getUploadedStage())
                        && task.getDesignerEmployeeNo().equals(file.getUploadedByEmployeeNo()))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATUS_CONFLICT,
                        "请由设计者在当前评审阶段重新上传终版文件"));
        List<OpinionSourceType> sources = ReviewType.PCB.name().equals(task.getReviewType())
                ? List.of(OpinionSourceType.MUTUAL_CHECK_ITEM, OpinionSourceType.MUTUAL_EXTRA)
                : List.of(OpinionSourceType.SCHEMATIC_REVIEW);
        boolean outdated = opinionMapper.findByTaskId(task.getId()).stream()
                .filter(opinion -> sources.contains(opinion.getSourceType()))
                .map(opinion -> opinion.getUpdatedAt() == null ? opinion.getCreatedAt() : opinion.getUpdatedAt())
                .anyMatch(updatedAt -> updatedAt != null && (finalFile.getUploadedAt() == null || finalFile.getUploadedAt().isBefore(updatedAt)));
        if (outdated) {
            //throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "请在评审意见全部处理完成后重新上传终版文件");
        }
    }

    /**
     * 检查指定类别是否至少有一个最新文件。
     * 缺少文件时抛出状态冲突并提示上传所需类别。
     */
    private void requireLatestStageFile(ReviewTaskRecord task, FileCategory category) {
        if (fileMapper.findLatestByTaskIdAndCategory(task.getId(), category.name()).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT,
                    missingFileMessage(category));
        }
    }

    /**
     * 按文件类别生成上传提示。
     * 工艺、结构、PCB 评审和原理图评审使用各自文案，其余类别使用通用提示。
     */
    private String missingFileMessage(FileCategory category) {
        return switch (category) {
            case PCB_PROCESS_REVIEW -> "请先上传工艺图文件";
            case PCB_STRUCTURE_REVIEW -> "请先上传结构图文件";
            case PCB_REVIEW -> "请先上传最新 PCB 评审文件";
            case SCHEMATIC_REVIEW -> "请先上传最新原理图评审文件";
            default -> "请先上传当前流程所需文件";
        };
    }

    /** 指定阶段必须同时满足：该阶段的全部意见（含未提交草稿）均已闭环，且已分配的每位评审人均已确认提交。 */
    private void requireStageCompleted(ReviewTaskRecord task, List<OpinionSourceType> sources,
                                       List<ReviewerSubmissionRequirement> requirements, String message) {
        requireOpinionsPassed(task.getId(), sources, message);
        requireReviewersSubmitted(task.getId(), requirements, message);
    }

    /**
     * 检查每个提交要求是否有对应工号和意见来源的正式记录。
     * 无意见确认也可作为记录，草稿不计入。
     * 失败时提示未提交的工号。
     */
    private void requireReviewersSubmitted(long taskId, List<ReviewerSubmissionRequirement> requirements, String message) {
        if (requirements.isEmpty()) {
            return;
        }
        List<com.bms.review.infrastructure.ReviewOpinionRecord> opinions = opinionMapper.findByTaskId(taskId);
        List<String> unsubmittedEmployeeNos = requirements.stream()
                .filter(requirement -> (opinions == null ? List.<com.bms.review.infrastructure.ReviewOpinionRecord>of() : opinions).stream()
                        .noneMatch(opinion -> !opinion.isDraft()
                                && requirement.employeeNo().equals(opinion.getRaisedByEmployeeNo())
                                && requirement.sources().contains(opinion.getSourceType())))
                .map(ReviewerSubmissionRequirement::employeeNo)
                .distinct()
                .toList();
        if (!unsubmittedEmployeeNos.isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT,
                    message + "；以下评审人尚未确认提交：" + String.join("、", unsubmittedEmployeeNos));
        }
    }

    /**
     * 生成 PCB 首轮提交要求，PCB、EMC 和硬件专家均使用 PCB_REVIEW 来源的记录。
     */
    private List<ReviewerSubmissionRequirement> pcbReviewSubmissionRequirements(ReviewTaskRecord task) {
        return reviewerSubmissionRequirements(task, List.of(
                new RoleSource(ReviewRole.PCB_EXPERT, List.of(OpinionSourceType.PCB_REVIEW)),
                new RoleSource(ReviewRole.EMC_EXPERT, List.of(OpinionSourceType.PCB_REVIEW)),
                new RoleSource(ReviewRole.HARDWARE_EXPERT, List.of(OpinionSourceType.PCB_REVIEW))));
    }

    /**
     * 生成工艺和结构阶段的提交要求，分别使用 PCB_PROCESS_REVIEW 和 PCB_STRUCTURE_REVIEW 来源。
     */
    private List<ReviewerSubmissionRequirement> pcbOptionalReviewSubmissionRequirements(ReviewTaskRecord task) {
        return reviewerSubmissionRequirements(task, List.of(
                new RoleSource(ReviewRole.PROCESS_EXPERT, List.of(OpinionSourceType.PCB_PROCESS_REVIEW)),
                new RoleSource(ReviewRole.STRUCTURE_EXPERT, List.of(OpinionSourceType.PCB_STRUCTURE_REVIEW))));
    }

    /**
     * 生成原理图专家提交要求，硬件、EMC 和 PCB 专家均使用 SCHEMATIC_REVIEW 来源。
     */
    private List<ReviewerSubmissionRequirement> schematicReviewSubmissionRequirements(ReviewTaskRecord task) {
        return reviewerSubmissionRequirements(task, List.of(
                new RoleSource(ReviewRole.HARDWARE_EXPERT, List.of(OpinionSourceType.SCHEMATIC_REVIEW)),
                new RoleSource(ReviewRole.EMC_EXPERT, List.of(OpinionSourceType.SCHEMATIC_REVIEW)),
                new RoleSource(ReviewRole.PCB_EXPERT, List.of(OpinionSourceType.SCHEMATIC_REVIEW))));
    }

    /**
     * 按互检分配快照生成提交要求。
     * 固定检查项或额外意见任一来源的正式记录均可满足记录校验。
     */
    private List<ReviewerSubmissionRequirement> mutualCheckSubmissionRequirements(ReviewTaskRecord task) {
        return TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getMutualCheckReviewerEmployeeNos()).stream()
                .map(employeeNo -> new ReviewerSubmissionRequirement(employeeNo,
                        List.of(OpinionSourceType.MUTUAL_CHECK_ITEM, OpinionSourceType.MUTUAL_EXTRA)))
                .toList();
    }

    /**
     * 把角色与意见来源规则展开成按工号检查的要求。
     * 优先使用角色分配快照，缺少快照的历史任务按已配置角色和组长工号回退。
     */
    private List<ReviewerSubmissionRequirement> reviewerSubmissionRequirements(ReviewTaskRecord task,
                                                                                  List<RoleSource> roleSources) {
        List<TaskReviewerAssignment> assignments = TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments());
        if (!assignments.isEmpty()) {
            return roleSources.stream().flatMap(roleSource -> assignments.stream()
                    .filter(assignment -> assignment.reviewRole() == roleSource.role())
                    .flatMap(assignment -> assignment.reviewerEmployeeNos().stream())
                    .map(employeeNo -> new ReviewerSubmissionRequirement(employeeNo, roleSource.sources())))
                    .distinct()
                    .toList();
        }
        Set<ReviewRole> configuredRoles = parseReviewRoles(task.getReviewRoles());
        if (task.getExpertLeaderEmployeeNo() == null || task.getExpertLeaderEmployeeNo().isBlank()) {
            return List.of();
        }
        return roleSources.stream().filter(roleSource -> configuredRoles.contains(roleSource.role()))
                .map(roleSource -> new ReviewerSubmissionRequirement(task.getExpertLeaderEmployeeNo(), roleSource.sources()))
                .distinct()
                .toList();
    }

    /**
     * 解析数据库中逗号分隔的角色编码，去除空白和重复值。
     * 无效历史编码跳过，空配置返回空集合。
     */
    private Set<ReviewRole> parseReviewRoles(String reviewRoles) {
        if (reviewRoles == null || reviewRoles.isBlank()) {
            return Set.of();
        }
        Set<ReviewRole> result = new LinkedHashSet<>();
        for (String value : reviewRoles.split(",")) {
            try {
                if (value != null && !value.isBlank()) {
                    result.add(ReviewRole.valueOf(value.trim()));
                }
            } catch (IllegalArgumentException ignored) {
                // 兼容历史任务中的无效角色值。
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 检查指定来源的意见是否闭环，来源列表为空时检查整个任务。
     * 确认通过和撤回视为闭环，其余状态会阻止流转。
     */
    private void requireOpinionsPassed(long taskId, List<OpinionSourceType> sources, String message) {
        List<com.bms.review.infrastructure.ReviewOpinionRecord> opinions = opinionMapper.findByTaskId(taskId);
        boolean passed = (opinions == null ? List.<com.bms.review.infrastructure.ReviewOpinionRecord>of() : opinions).stream()
                .filter(opinion -> sources.isEmpty() || sources.contains(opinion.getSourceType()))
                .allMatch(opinion -> OpinionStatus.CONFIRMED_PASS.name().equals(opinion.getStatus())
                        || OpinionStatus.WITHDRAWN.name().equals(opinion.getStatus()));
        if (!passed) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, message);
        }
    }

    /**
     * 按任务 ID 读取任务，查不到时抛出任务不存在异常。
     * 需要防止并发写入的调用方先取得行锁。
     */
    private ReviewTaskRecord requireTask(long taskId) {
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        }
        return task;
    }

    /** 追加流转日志，记录动作、原因、前后节点、下一步处理人及结束确认角色。 */
    private void appendHistory(TransitionContext context) {
        WorkflowAction action = context.action();
        String comment = context.comment();
        TaskFlowRecord record = new TaskFlowRecord();
        record.setId(flowMapper.nextId());
        record.setTaskId(context.task().getId());
        record.setAction(action.name());
        record.setActionName(action == WorkflowAction.RETURN
                ? action.actionName() + "至" + context.plan().targetStatus().displayName() : action.actionName());
        record.setOperateEmployeeNo(context.operatorEmployeeNo());
        record.setComment(comment == null || comment.isBlank() ? action.actionName() : comment);
        record.setFromStatus(context.fromStatus().name());
        record.setToStatus(context.plan().targetStatus().name());
        record.setNextOperatorEmployeeNos(nextOperatorEmployeeNos(context));
        if (context.plan().approvalRole() != null) {
            record.setApprovalRole(context.plan().approvalRole().code());
        }
        flowMapper.insert(record);
    }

    /** 为流转日志生成下一步处理人快照，按节点补全设计者、分配组长或硬件部经理。 */
    private String nextOperatorEmployeeNos(TransitionContext context) {
        ReviewTaskRecord task = context.task();
        return switch (context.plan().targetStatus()) {
            case PENDING_INITIATION -> task.getDesignerEmployeeNo();
            case MUTUAL_CHECK_PENDING_ASSIGNMENT -> ReviewType.PCB.name().equals(task.getReviewType())
                    ? TaskReviewerAssignmentCodec.encodeReviewerEmployeeNos(
                            reviewerWhitelistApplicationService.findSystemRoleEmployeeNos(SystemRole.PCB_LEADER))
                    : task.getExpertLeaderEmployeeNo();
            case SCHEMATIC_EXPERT_PENDING_ASSIGNMENT -> TaskReviewerAssignmentCodec.encodeReviewerEmployeeNos(
                    reviewerWhitelistApplicationService.findSystemRoleEmployeeNos(SystemRole.HARDWARE_MANAGER));
            default -> task.getAssignedReviewerEmployeeNos();
        };
    }

    public record TransitionCommand(WorkflowAction action, String comment, List<String> reviewerEmployeeNos,
                                    List<TaskReviewerAssignment> reviewerAssignments) {
        /**
         * 校验人员工号非空白，去除首尾空格并去重。
         * 将省略的列表转为空列表，并复制按角色分配集合。
         */
        public TransitionCommand {
            if (reviewerEmployeeNos != null && reviewerEmployeeNos.stream().anyMatch(employeeNo -> employeeNo == null || employeeNo.isBlank())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评审人员工号不能为空");
            }
            reviewerEmployeeNos = reviewerEmployeeNos == null ? List.of() : reviewerEmployeeNos.stream().map(String::trim).distinct().toList();
            reviewerAssignments = reviewerAssignments == null ? List.of() : List.copyOf(reviewerAssignments);
        }

        /**
         * 构造不含按角色分配的单动作命令，用于普通动作或仅按工号分配的互检动作。
         */
        public TransitionCommand(WorkflowAction action, String comment, List<String> reviewerEmployeeNos) {
            this(action, comment, reviewerEmployeeNos, List.of());
        }
    }

    /** 前端 transition 请求模型；工艺和结构评审可分别提交，也可同时出现，其余动作一次只能提交一个。 */
    public record TransitionBatchCommand(List<WorkflowAction> actions, String comment, List<String> reviewerEmployeeNos,
                                         List<TaskReviewerAssignment> reviewerAssignments) {
        /**
         * 校验动作列表非空且不含空动作，保留请求顺序，并归一化人员列表。
         * 动作能否组合由执行阶段判断。
         */
        public TransitionBatchCommand {
            if (actions == null || actions.isEmpty() || actions.stream().anyMatch(action -> action == null)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
            }
            actions = List.copyOf(actions);
            reviewerEmployeeNos = reviewerEmployeeNos == null ? List.of() : reviewerEmployeeNos.stream().map(String::trim).distinct().toList();
            reviewerAssignments = reviewerAssignments == null ? List.of() : List.copyOf(reviewerAssignments);
        }

        /**
         * 构造不含按角色分配的批量命令，保留原有动作顺序及流转说明。
         */
        public TransitionBatchCommand(List<WorkflowAction> actions, String comment, List<String> reviewerEmployeeNos) {
            this(actions, comment, reviewerEmployeeNos, List.of());
        }
    }

    /** 与 multipart 文件一一对应的受控文件类别；支持工艺、结构及进入待结束时的终版文件。 */
    public record TransitionFileCommand(FileCategory fileCategory, MultipartFile file) { }

    public record WorkflowView(Long taskId, TaskStatus fromStatus, TaskStatus toStatus,
                               List<String> assignedReviewerEmployeeNos) {
        /**
         * 复制当前处理人工号列表，使返回值保留本次流转后的人员快照。
         */
        public WorkflowView {
            assignedReviewerEmployeeNos = List.copyOf(assignedReviewerEmployeeNos);
        }
    }

    /** 某位评审人必须针对 sources 中任一来源完成正式提交；互检固定项和额外意见属于同一提交阶段。 */
    private record ReviewerSubmissionRequirement(String employeeNo, List<OpinionSourceType> sources) { }

    private record RoleSource(ReviewRole role, List<OpinionSourceType> sources) { }

    /** 当前动作的状态变更计划；finishing 为 true 时才会执行结束任务的快照、归档和邮件。 */
    private record TransitionPlan(TaskStatus targetStatus, boolean finishing, SystemRole approvalRole,
                                  List<String> pendingApprovers) {
        /**
         * 构造未指定本次确认角色及剩余待确认人员的流转计划。
         */
        private TransitionPlan(TaskStatus targetStatus, boolean finishing) {
            this(targetStatus, finishing, null, List.of());
        }
    }

    /** 保存一次流转已确认的任务、操作人、原状态、目标计划和人员分配，供写入任务及审计时使用。 */
    private record TransitionContext(ReviewTaskRecord task, WorkflowAction action, String comment, String operatorEmployeeNo,
                                     TaskStatus fromStatus, TransitionPlan plan, List<String> reviewerEmployeeNos,
                                     List<TaskReviewerAssignment> reviewerAssignments) {
        /**
         * 根据已更新的任务记录返回任务 ID、前后状态及当前处理人工号。
         */
        private WorkflowView toView() {
            return new WorkflowView(task.getId(), fromStatus, plan.targetStatus(),
                    TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getAssignedReviewerEmployeeNos()));
        }
    }
}
