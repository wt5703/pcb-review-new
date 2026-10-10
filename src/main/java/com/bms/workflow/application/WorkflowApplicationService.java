package com.bms.workflow.application;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.application.FileApplicationService;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.file.domain.FileCategory;
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
import com.bms.task.domain.TaskStatus;
import com.bms.task.domain.TaskReviewerAssignment;
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
import java.util.List;
import java.util.Set;

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
    private final FileApplicationService fileApplicationService;
    private final TaskCheckItemApplicationService checkItemApplicationService;
    private final TaskFlowMapper flowMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final TaskArchiveApplicationService taskArchiveApplicationService;
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService;
    private final ReviewMailNotificationApplicationService reviewMailNotificationApplicationService;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

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

    /** 返回创建任务时可选择的专家白名单；候选角色只由评审类型决定，不依赖任务状态。 */
    public List<ReviewerWhitelistApplicationService.AssignableReviewerView> listAssignableReviewers(ReviewType reviewType) {
        List<ReviewerWhitelistRole> roles = reviewType == ReviewType.PCB
                ? List.of(ReviewerWhitelistRole.EMC_EXPERT, ReviewerWhitelistRole.PCB_EXPERT)
                : List.of(ReviewerWhitelistRole.HARDWARE_EXPERT);
        return reviewerWhitelistApplicationService.listAssignableUsers(roles);
    }

    /** 互检单分配候选人与任务创建专家候选人职责不同，单独保留受权限保护的查询入口。 */
    public List<ReviewerWhitelistApplicationService.AssignableReviewerView> listMutualCheckReviewers(ReviewType reviewType,
                                                                                                         CurrentUser currentUser) {
        ReviewerWhitelistRole role = reviewType == ReviewType.PCB
                ? ReviewerWhitelistRole.PCB_MUTUAL_CHECK : ReviewerWhitelistRole.SCHEMATIC_MUTUAL_CHECK;
        Permission permission = reviewType == ReviewType.PCB
                ? Permission.ASSIGN_PCB_MUTUAL_CHECK : Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK;
        if (!permissionPolicy.has(currentUser.roles(), permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无互检人员分配权限");
        }
        return reviewerWhitelistApplicationService.listAssignableUsers(List.of(role));
    }

    @Transactional
    public WorkflowView transition(long taskId, WorkflowAction action, String comment, CurrentUser currentUser) {
        return transition(taskId, new TransitionCommand(action, comment, List.of()), currentUser);
    }

    /**
     * 对外流程请求使用 actions 数组。PCB 工艺、结构评审既可分别开启，也可在同一事务中同时开启；
     * 其余业务动作必须单独提交，避免一次请求跨越多个无关节点。
     */
    @Transactional
    public WorkflowView transition(long taskId, TransitionBatchCommand command, CurrentUser currentUser) {
        return transition(taskId, command, List.of(), currentUser);
    }

    /**
     * 在开启 PCB 工艺/结构评审时接收本次阶段文件：文件上传、旧版本淘汰和流程推进处于同一业务事务中。
     * 普通 JSON 流转仍复用无文件重载，保持既有调用兼容。
     */
    @Transactional
    public WorkflowView transition(long taskId, TransitionBatchCommand command, List<TransitionFileCommand> files,
                                   CurrentUser currentUser) {
        List<TransitionFileCommand> transitionFiles = files == null ? List.of() : List.copyOf(files);
        validateTransitionFiles(command, transitionFiles);
        // 文件类别与本次开启动作一一匹配；FileApplicationService 负责资源上传、任务绑定及同类旧文件置历史。
        transitionFiles.forEach(file -> fileApplicationService.upload(taskId, file.fileCategory(), file.file(), currentUser));
        return transitionAfterFilesUploaded(taskId, command, currentUser);
    }

    private WorkflowView transitionAfterFilesUploaded(long taskId, TransitionBatchCommand command, CurrentUser currentUser) {
        if (command == null || command.actions().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
        }
        if (command.actions().size() == 1) {
            WorkflowAction action = command.actions().get(0);
            WorkflowView view = transition(taskId, new TransitionCommand(action, command.comment(), command.reviewerEmployeeNos()), currentUser);
            notifyPcbStageReviewStarted(taskId, List.of(action));
            return view;
        }
        if (!isPcbStageReviewPair(command.actions())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "一次只能提交一个流程动作；仅 PCB 工艺评审和结构评审允许同时提交");
        }
        if (!command.reviewerEmployeeNos().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "开启工艺和结构评审时不能同时分配人员");
        }
        // 文件已由文件接口完成上传和落库；这里固定先开启结构评审，再开启工艺评审。
        WorkflowView structureView = transition(taskId, new TransitionCommand(WorkflowAction.START_PCB_STRUCTURE_REVIEW,
                command.comment(), List.of()), currentUser);
        WorkflowView processView = transition(taskId, new TransitionCommand(WorkflowAction.START_PCB_PROCESS_REVIEW,
                command.comment(), List.of()), currentUser);
        notifyPcbStageReviewStarted(taskId, command.actions());
        return new WorkflowView(taskId, structureView.fromStatus(), processView.toStatus(), List.of());
    }

    private void validateTransitionFiles(TransitionBatchCommand command, List<TransitionFileCommand> files) {
        if (files.isEmpty()) {
            return;
        }
        if (command == null || command.actions() == null || command.actions().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "上传流程文件时必须提供流程动作");
        }
        Set<FileCategory> expectedCategories = command.actions().stream()
                .map(this::stageFileCategoryFor)
                .collect(java.util.stream.Collectors.toSet());
        if (expectedCategories.size() != command.actions().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "仅开启工艺或结构评审时支持随流程上传文件，且流程动作不能重复");
        }
        Set<FileCategory> providedCategories = files.stream().map(TransitionFileCommand::fileCategory)
                .collect(java.util.stream.Collectors.toSet());
        if (providedCategories.size() != files.size() || !providedCategories.equals(expectedCategories)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程文件类型必须与开启的工艺/结构评审动作一一对应");
        }
        if (files.stream().anyMatch(file -> file.file() == null || file.file().isEmpty())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程文件不能为空");
        }
    }

    private FileCategory stageFileCategoryFor(WorkflowAction action) {
        return switch (action) {
            case START_PCB_PROCESS_REVIEW -> FileCategory.PCB_PROCESS_REVIEW;
            case START_PCB_STRUCTURE_REVIEW -> FileCategory.PCB_STRUCTURE_REVIEW;
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持上传文件");
        };
    }

    /** 仅将本次实际开启的工艺或结构评审通知给相应专家，并附上对应类型的最新文件。 */
    private void notifyPcbStageReviewStarted(long taskId, List<WorkflowAction> actions) {
        List<FileCategory> fileCategories = actions.stream()
                .filter(this::isPcbStageReviewAction)
                .map(action -> action == WorkflowAction.START_PCB_PROCESS_REVIEW
                        ? FileCategory.PCB_PROCESS_REVIEW : FileCategory.PCB_STRUCTURE_REVIEW)
                .toList();
        if (!fileCategories.isEmpty()) {
            reviewMailNotificationApplicationService.enqueuePcbStageReview(requireTask(taskId), fileCategories);
        }
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

    /** 统一编排一次流程推进；文件已通过批量流程入口写入后，在这里校验其最新版本是否满足节点要求。 */
    @Transactional
    public WorkflowView transition(long taskId, TransitionCommand command, CurrentUser currentUser) {
        // 1. 只做校验和目标状态计算；此阶段不写任务状态，校验失败不会留下半成品数据。
        TransitionContext context = prepareTransition(taskId, command, currentUser);

        // 2. 原子地写入当前节点人员、任务状态、流转记录和状态变更事件，四者始终保持一致。
        persistTransition(context);

        // 3. 只有状态已成功写入后，才执行结束任务的归档和邮件等后置动作。
        executePostTransition(context);
        return context.toView();
    }

    /**
     * 组装一次已通过全部前置校验的流转上下文。
     * 普通节点和结束节点的差异只在 {@link #resolveTransitionPlan} 中处理，入口不再散落条件分支。
     */
    private TransitionContext prepareTransition(long taskId, TransitionCommand command, CurrentUser currentUser) {
        requireTransitionCommand(command);
        ReviewTaskRecord task = requireTask(taskId);
        WorkflowAction action = command.action();
        requireTaskCanTransition(task, action);
        validatePayload(task, action, command);

        // 先确认当前用户、当前状态和前序意见允许执行该动作，再确定最终状态。
        TransitionPlan plan = resolveTransitionPlan(task, action, currentUser);
        // 文件在文件域上传完成；流程域只校验本次节点所需的最新文件是否已存在。
        validateStageFilePresence(task, action);
        // 结束前需要冻结检查项快照，且必须发生在任务状态变为 FINISHED 之前。
        freezeCheckItemsBeforeFinish(task, plan);
        return new TransitionContext(task, action, command.comment(), currentUser.employeeNo(),
                TaskStatus.valueOf(task.getStatus()), plan, command.reviewerEmployeeNos());
    }

    /** 将状态变更、流程审计和供异步消费者使用的事件作为同一笔事务数据写入。 */
    private void persistTransition(TransitionContext context) {
        refreshActiveReviewers(context);
        persistStatus(context.task(), context.plan().targetStatus());
        persistActiveReviewers(context.task());
        appendHistory(context.task().getId(), context.action(), context.operatorEmployeeNo(), context.comment());
        publishStatusChangedEvent(context.task().getId(), context.action(), context.plan().targetStatus());
    }

    /**
     * assigned_reviewer_employee_nos 只保存当前已开启节点的待处理人员：
     * - PCB 首轮仅 PCB、EMC 专家，工艺/结构在各自节点开启时追加；
     * - 互检使用本次分配的人员；原理图评审直接使用创建任务时指定的专家；
     * - 已通过前一节点校验的历史人员在进入下一节点时清理，兼容旧数据中曾预写全部人员的情况。
     */
    private void refreshActiveReviewers(TransitionContext context) {
        ReviewTaskRecord task = context.task();
        Set<String> activeReviewerEmployeeNos = new LinkedHashSet<>(TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getAssignedReviewerEmployeeNos()));
        switch (context.action()) {
            case START_PCB_PROCESS_REVIEW, START_PCB_STRUCTURE_REVIEW -> {
                // 进入工艺/结构前已校验 PCB、EMC 专家全部提交，故不再保留其待办。
                activeReviewerEmployeeNos.removeAll(reviewerEmployeeNosForRoles(task, ReviewRole.PCB_EXPERT, ReviewRole.EMC_EXPERT));
                activeReviewerEmployeeNos.addAll(context.action() == WorkflowAction.START_PCB_PROCESS_REVIEW
                        ? reviewerEmployeeNosForRoles(task, ReviewRole.PROCESS_EXPERT)
                        : reviewerEmployeeNosForRoles(task, ReviewRole.STRUCTURE_EXPERT));
            }
            case START_PCB_MATUAL_REVIEW, START_SCHEMATIC_MATUAL_REVIEW -> {
                // 互检人员以本次分配为准，且前序阶段意见已在流转校验中闭环。
                activeReviewerEmployeeNos.clear();
                activeReviewerEmployeeNos.addAll(context.reviewerEmployeeNos());
            }
            case START_SCHEMATIC_EXPERT_REVIEW -> {
                // 原理图互检意见已闭环，切换到创建任务时已指定的原理图评审专家。
                activeReviewerEmployeeNos.clear();
                activeReviewerEmployeeNos.addAll(reviewerEmployeeNosForRoles(task, ReviewRole.HARDWARE_EXPERT,
                        ReviewRole.EMC_EXPERT, ReviewRole.PCB_EXPERT));
            }
            case FINISH -> activeReviewerEmployeeNos.clear();
            default -> { }
        }
        task.setAssignedReviewerEmployeeNos(TaskReviewerAssignmentCodec.encodeReviewerEmployeeNos(activeReviewerEmployeeNos));
    }

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

    private void publishStatusChangedEvent(long taskId, WorkflowAction action, TaskStatus targetStatus) {
        outboxEventMapper.insert(new OutboxEventRecord("TASK_STATUS_CHANGED", "REVIEW_TASK", taskId,
                "{\"taskId\":" + taskId + ",\"action\":\"" + action.name() + "\",\"toStatus\":\"" + targetStatus.name() + "\"}", "PENDING"));
    }

    /** 普通节点没有后置副作用；结束节点状态落库成功后才归档并发送终版图邮件。 */
    private void executePostTransition(TransitionContext context) {
        if (context.plan().finishing()) {
            taskArchiveApplicationService.archive(context.task());
            reviewMailNotificationApplicationService.enqueueTaskFinished(context.task());
        }
    }

    private void requireTransitionCommand(TransitionCommand command) {
        if (command == null || command.action() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
        }
    }

    private void requireTaskCanTransition(ReviewTaskRecord task, WorkflowAction action) {
        if (TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许重新打开或流转");
        }
        if (action == WorkflowAction.CREATE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CREATE 只能通过任务提交接口执行，不能调用流程推进接口");
        }
    }

    private TransitionPlan resolveTransitionPlan(ReviewTaskRecord task, WorkflowAction action, CurrentUser currentUser) {
        if (action == WorkflowAction.FINISH) {
            return resolveFinishPlan(task, currentUser);
        }
        validateActionPrerequisites(task, action);
        return new TransitionPlan(resolveNonFinishTargetStatus(task, action, currentUser), false);
    }

    /** 所有“真正结束任务”的判断集中在此处，避免与普通节点推进混在一起。 */
    private TransitionPlan resolveFinishPlan(ReviewTaskRecord task, CurrentUser currentUser) {
        requireOpinionsPassed(task.getId(), List.of(), "存在未确认通过的意见，不能结束任务");
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        return new TransitionPlan(requireFinishTransition(reviewType, current, currentUser), true);
    }

    /** 结束前先冻结任务检查项快照，随后才更新状态并归档。 */
    private void freezeCheckItemsBeforeFinish(ReviewTaskRecord task, TransitionPlan plan) {
        if (plan.finishing()) {
            checkItemApplicationService.materializeActiveTaskItems(task.getId());
        }
    }

    private void persistStatus(ReviewTaskRecord task, TaskStatus targetStatus) {
        task.setStatus(targetStatus.name());
        if (taskMapper.update(task) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
    }

    private void persistActiveReviewers(ReviewTaskRecord task) {
        if (taskMapper.updateAssignedReviewerEmployeeNos(task) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
    }

    private void validatePayload(ReviewTaskRecord task, WorkflowAction action, TransitionCommand command) {
        boolean requiresAssignment = action == WorkflowAction.START_PCB_MATUAL_REVIEW
                || action == WorkflowAction.START_SCHEMATIC_MATUAL_REVIEW;
        if (requiresAssignment && command.reviewerEmployeeNos().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "进入该评审节点时必须指定至少一名评审人员");
        }
        if (!requiresAssignment && !command.reviewerEmployeeNos().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持分配评审人员");
        }
    }

    private TaskStatus resolveNonFinishTargetStatus(ReviewTaskRecord task, WorkflowAction action, CurrentUser currentUser) {
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        return switch (action) {
            case CREATE, FINISH -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不能按普通节点推进");
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
            case START_SCHEMATIC_EXPERT_REVIEW -> requireDesignerTransition(task,
                    reviewType == ReviewType.SCHEMATIC && current == TaskStatus.MUTUAL_CHECK_REVIEWING,
                    TaskStatus.SCHEMATIC_REVIEWING, currentUser);
            case PREPARE_FINISH -> requireDesignerTransition(task,
                    (reviewType == ReviewType.PCB && current == TaskStatus.MUTUAL_CHECK_REVIEWING)
                            || (reviewType == ReviewType.SCHEMATIC && current == TaskStatus.SCHEMATIC_REVIEWING),
                    current, currentUser);
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
        if (!task.getDesignerEmployeeNo().equals(currentUser.employeeNo()) && !permissionPolicy.isAdministrator(currentUser.roles())) {
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
            case START_PCB_STRUCTURE_REVIEW, START_PCB_PROCESS_REVIEW -> {
                requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.PCB_REVIEW), "专家评审意见尚未全部确认通过，不能开启工艺或结构评审");
                requirePcbExpertsSubmitted(task.getId());
            }
            case START_PCB_MATUAL_ASSIGNMENT ->
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.PCB_REVIEW, OpinionSourceType.PCB_PROCESS_REVIEW,
                            OpinionSourceType.PCB_STRUCTURE_REVIEW), "专家、工艺或结构评审意见尚未全部确认通过，不能开启互检单分配");
            case START_SCHEMATIC_EXPERT_REVIEW ->
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.MUTUAL_CHECK_ITEM, OpinionSourceType.MUTUAL_EXTRA),
                            "互检单意见尚未全部确认通过，不能开启原理图评审");
            case PREPARE_FINISH -> {
                if (ReviewType.PCB.name().equals(task.getReviewType())) {
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.MUTUAL_CHECK_ITEM, OpinionSourceType.MUTUAL_EXTRA),
                            "互检单意见尚未全部确认通过，不能准备结束任务");
                } else {
                    requireOpinionsPassed(task.getId(), List.of(OpinionSourceType.SCHEMATIC_REVIEW),
                            "原理图专家评审意见尚未全部确认通过，不能准备结束任务");
                }
            }
            default -> { }
        }
    }

    /**
     * PCB 首轮专家评审结束后才能进入工艺/结构评审。除了意见已闭环外，任务中已分配的
     * 硬件、EMC、PCB 专家都必须显式提交实际意见或确认无意见，避免遗漏已分配专家。
     */
    private void requirePcbExpertsSubmitted(long taskId) {
        ReviewTaskRecord task = requireTask(taskId);
        List<String> assignedExpertEmployeeNos = TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments()).stream()
                .filter(assignment -> assignment.reviewRole() == ReviewRole.HARDWARE_EXPERT
                        || assignment.reviewRole() == ReviewRole.EMC_EXPERT
                        || assignment.reviewRole() == ReviewRole.PCB_EXPERT)
                .flatMap(assignment -> assignment.reviewerEmployeeNos().stream())
                .distinct()
                .toList();
        // 兼容旧任务：尚未保存“角色—专家”映射时仍沿用历史组长作为唯一专家。
        if (assignedExpertEmployeeNos.isEmpty() && task.getExpertLeaderEmployeeNo() != null) {
            assignedExpertEmployeeNos = List.of(task.getExpertLeaderEmployeeNo());
        }
        List<String> requiredReviewerEmployeeNos = assignedExpertEmployeeNos;
        boolean allSubmitted = !requiredReviewerEmployeeNos.isEmpty() && requiredReviewerEmployeeNos.stream().allMatch(reviewerEmployeeNo ->
                opinionMapper.findByTaskId(taskId).stream().anyMatch(opinion -> OpinionSourceType.PCB_REVIEW == opinion.getSourceType()
                        && reviewerEmployeeNo.equals(opinion.getRaisedByEmployeeNo())));
        if (!allSubmitted) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT,
                    "仍有已分配专家未提交评审意见或确认无意见，不能开启工艺或结构评审");
        }
    }

    /** 流程推进前确认文件接口已完成当前阶段文件的上传和落库。 */
    private void validateStageFilePresence(ReviewTaskRecord task, WorkflowAction action) {
        if (action == WorkflowAction.START_PCB_MATUAL_ASSIGNMENT) {
            requireLatestStageFile(task, FileCategory.PCB_PROCESS_REVIEW);
            requireLatestStageFile(task, FileCategory.PCB_STRUCTURE_REVIEW);
            return;
        }
        FileCategory category = switch (action) {
            case START_PCB_PROCESS_REVIEW -> FileCategory.PCB_PROCESS_REVIEW;
            case START_PCB_STRUCTURE_REVIEW -> FileCategory.PCB_STRUCTURE_REVIEW;
            case START_SCHEMATIC_EXPERT_REVIEW -> FileCategory.SCHEMATIC_REVIEW;
            case PREPARE_FINISH -> ReviewType.SCHEMATIC.name().equals(task.getReviewType()) ? FileCategory.SCHEMATIC_REVIEW : null;
            default -> null;
        };
        if (category != null) {
            requireLatestStageFile(task, category);
        }
    }

    private void requireLatestStageFile(ReviewTaskRecord task, FileCategory category) {
        if (fileMapper.findLatestByTaskIdAndCategory(task.getId(), category.name()).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT,
                    missingFileMessage(category));
        }
    }

    private String missingFileMessage(FileCategory category) {
        return switch (category) {
            case PCB_PROCESS_REVIEW -> "请先上传工艺图文件";
            case PCB_STRUCTURE_REVIEW -> "请先上传结构图文件";
            case PCB_REVIEW -> "请先上传最新 PCB 评审文件";
            case SCHEMATIC_REVIEW -> "请先上传最新原理图评审文件";
            default -> "请先上传当前流程所需文件";
        };
    }

    /** sources 为空时表示该任务全部意见都必须闭环。 */
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

    private void appendHistory(long taskId, WorkflowAction action, String operateEmployeeNo, String comment) {
        TaskFlowRecord record = new TaskFlowRecord();
        record.setId(flowMapper.nextId());
        record.setTaskId(taskId);
        record.setAction(action.name());
        record.setActionName(action.actionName());
        record.setOperateEmployeeNo(operateEmployeeNo);
        record.setComment(comment);
        flowMapper.insert(record);
    }

    public record TransitionCommand(WorkflowAction action, String comment, List<String> reviewerEmployeeNos) {
        public TransitionCommand {
            if (reviewerEmployeeNos != null && reviewerEmployeeNos.stream().anyMatch(employeeNo -> employeeNo == null || employeeNo.isBlank())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评审人员工号不能为空");
            }
            reviewerEmployeeNos = reviewerEmployeeNos == null ? List.of() : reviewerEmployeeNos.stream().map(String::trim).distinct().toList();
        }
    }

    /** 前端 transition 请求模型；工艺和结构评审可分别提交，也可同时出现，其余动作一次只能提交一个。 */
    public record TransitionBatchCommand(List<WorkflowAction> actions, String comment, List<String> reviewerEmployeeNos) {
        public TransitionBatchCommand {
            if (actions == null || actions.isEmpty() || actions.stream().anyMatch(action -> action == null)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "流程动作不能为空");
            }
            actions = List.copyOf(actions);
            reviewerEmployeeNos = reviewerEmployeeNos == null ? List.of() : reviewerEmployeeNos.stream().map(String::trim).distinct().toList();
        }
    }

    /** 与 multipart 文件一一对应的受控文件类别；仅允许工艺或结构评审文件随流程上传。 */
    public record TransitionFileCommand(FileCategory fileCategory, MultipartFile file) { }

    public record WorkflowView(Long taskId, TaskStatus fromStatus, TaskStatus toStatus,
                               List<String> assignedReviewerEmployeeNos) {
        public WorkflowView {
            assignedReviewerEmployeeNos = List.copyOf(assignedReviewerEmployeeNos);
        }
    }

    /** 当前动作的状态变更计划；finishing 为 true 时才会执行结束任务的快照、归档和邮件。 */
    private record TransitionPlan(TaskStatus targetStatus, boolean finishing) { }

    /** 一次流转中已确认的输入、原状态和目标状态，避免持久化阶段再次重新判断业务规则。 */
    private record TransitionContext(ReviewTaskRecord task, WorkflowAction action, String comment, String operatorEmployeeNo,
                                     TaskStatus fromStatus, TransitionPlan plan, List<String> reviewerEmployeeNos) {
        private WorkflowView toView() {
            return new WorkflowView(task.getId(), fromStatus, plan.targetStatus(),
                    TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getAssignedReviewerEmployeeNos()));
        }
    }
}
