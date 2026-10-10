package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.bms.task.application.TaskProcessorAuthorizationService;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.domain.OpinionSeverity;
import com.bms.review.domain.OpinionStatus;
import com.bms.review.domain.ReplyType;
import com.bms.review.domain.ReviewRole;
import com.bms.review.infrastructure.OpinionConfirmationRecord;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.review.infrastructure.TaskCheckItemMapper;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.domain.TaskReviewerAssignmentCodec;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.domain.WorkflowAction;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 编排统一评审意见的提出、设计者答复、提出人确认和撤回，并将固定检查项不合格与互检额外意见纳入相同的可追溯闭环。
 */
@Service
public class OpinionApplicationService {
    private final ReviewOpinionMapper opinionMapper;
    private final ReviewTaskMapper taskMapper;
    private final TaskCheckItemMapper taskCheckItemMapper;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final TaskProcessorAuthorizationService taskProcessorAuthorizationService;
    private final TaskFlowMapper flowMapper;
    private final UserCenterUserProfileClient userProfileClient;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    @Autowired
    public OpinionApplicationService(ReviewOpinionMapper opinionMapper, ReviewTaskMapper taskMapper, TaskCheckItemMapper taskCheckItemMapper,
                                     TaskAssignmentAccessMapper assignmentAccessMapper, TaskProcessorAuthorizationService taskProcessorAuthorizationService,
                                     TaskFlowMapper flowMapper, UserCenterUserProfileClient userProfileClient) {
        this.opinionMapper = opinionMapper;
        this.taskMapper = taskMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.taskProcessorAuthorizationService = taskProcessorAuthorizationService;
        this.flowMapper = flowMapper;
        this.userProfileClient = userProfileClient;
    }

    /**
     * @author 王涛
     * @date 2026-09-18
     * @description 提出意见并保存明确来源：PCB_REVIEW、PCB_PROCESS_REVIEW、PCB_STRUCTURE_REVIEW、SCHEMATIC_REVIEW 或互检来源；运行时由 Spring 注入完整依赖以支持未提交专家汇总。
     */
    @Transactional
    public OpinionView raise(RaiseOpinionCommand command, CurrentUser currentUser) {
        ReviewTaskRecord task = requireOpenTask(command.taskId());
        taskProcessorAuthorizationService.requireCurrentTaskProcessor(command.taskId(), currentUser);
        if (!permissionPolicy.has(currentUser.roles(), Permission.FILL_OPINION)
                && command.sourceType() != OpinionSourceType.MUTUAL_CHECK_ITEM && command.sourceType() != OpinionSourceType.MUTUAL_EXTRA) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无提出评审意见权限");
        }
        if (command.sourceType() == OpinionSourceType.MUTUAL_CHECK_ITEM
                && (command.sourceItemId() == null || taskCheckItemMapper.findByTaskIdAndId(command.taskId(), command.sourceItemId()) == null)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务");
        }
        ReviewOpinionRecord record = new ReviewOpinionRecord();
        record.setId(opinionMapper.nextOpinionId());
        record.setTaskId(command.taskId());
        record.setSourceType(command.sourceType());
        record.setSourceItemId(command.sourceItemId());
        record.setSeverity(command.severity() == null ? OpinionSeverity.GENERAL : command.severity());
        record.setNoOpinion(false);
        requireComment(command.comment());
        String richText = requireComment(command.richText() == null ? command.comment() : command.richText());
        record.setComment(command.comment());
        record.setRichText(richText);
        record.setRaisedByEmployeeNo(currentUser.employeeNo());
        record.setRaisedByName(currentUser.resolvedDisplayName());
        record.setStatus(OpinionStatus.PENDING_REPLY.name());
        opinionMapper.insert(record);
        return toView(record);
    }

    /**
     * 评审人确认当前阶段没有意见。该操作不触发工作流，而是持久化一条已通过的
     * 已确认的无意见记录，使“已提交无意见”与实际评审意见一样可审计、可追溯。
     */
    @Transactional
    public OpinionView submitNoOpinion(long taskId, OpinionSourceType sourceType, CurrentUser currentUser) {
        ReviewTaskRecord task = requireOpenTask(taskId);
        taskProcessorAuthorizationService.requireCurrentTaskProcessor(taskId, currentUser);
        if (!isNoOpinionSourceAvailable(task, sourceType)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前评审节点不支持提交无意见");
        }
        boolean alreadySubmitted = opinionMapper.findByTaskId(taskId).stream()
                .anyMatch(item -> currentUser.employeeNo().equals(item.getRaisedByEmployeeNo()) && sourceType == item.getSourceType());
        if (alreadySubmitted) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "当前阶段已提交评审意见，不能重复提交无意见");
        }
        ReviewOpinionRecord record = new ReviewOpinionRecord();
        record.setId(opinionMapper.nextOpinionId());
        record.setTaskId(taskId);
        record.setSourceType(sourceType);
        record.setSeverity(OpinionSeverity.GENERAL);
        record.setNoOpinion(true);
        record.setComment("无意见，确认提交");
        record.setRichText("无意见，确认提交");
        record.setRaisedByEmployeeNo(currentUser.employeeNo());
        record.setRaisedByName(currentUser.resolvedDisplayName());
        record.setStatus(OpinionStatus.CONFIRMED_PASS.name());
        opinionMapper.insert(record);
        releaseReviewerWhenCurrentStagesCompleted(taskId, currentUser.employeeNo(), sourceType);
        return toView(record);
    }

    /** 编辑仅限尚未被设计者答复的本人意见，防止改变已进入闭环的评审事实。 */
    @Transactional
    public OpinionView update(long opinionId, UpdateOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        requireOpenTask(opinion.getTaskId());
        requireOpinionOwner(opinion, currentUser, "编辑");
        requireNoDesignerReply(opinionId);
        requireComment(command.comment());
        String richText = requireComment(command.richText() == null ? command.comment() : command.richText());
        opinion.setSeverity(command.severity() == null ? opinion.getSeverity() : command.severity());
        opinion.setComment(command.comment());
        opinion.setRichText(richText);
        if (opinionMapper.updateUnrepliedOpinion(opinion) != 1) {
            // 数据库更新条件覆盖状态与答复历史；失败后再次读取答复历史，给调用方返回准确原因。
            requireNoDesignerReply(opinionId);
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "当前意见状态不允许编辑，请刷新后重试");
        }
        return toView(opinion);
    }

    /** 删除仅限尚未被设计者答复的本人意见；流程审计记录会保留。 */
    @Transactional
    public void delete(long opinionId, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        requireOpenTask(opinion.getTaskId());
        requirePendingReplyOwner(opinion, currentUser, "删除");
        if (opinionMapper.deletePendingReply(opinionId) != 1) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "意见已被答复，不能删除");
        }
    }

    @Transactional
    public OpinionView reply(long opinionId, ReplyOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        ReviewTaskRecord task = requireOpenTask(opinion.getTaskId());
        if (!task.getDesignerEmployeeNo().equals(currentUser.employeeNo()) && !permissionPolicy.isAdministrator(currentUser.roles())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有任务设计者可以答复意见");
        }
        if (!OpinionStatus.PENDING_REPLY.name().equals(opinion.getStatus()) && !OpinionStatus.CONFIRMED_REJECTED.name().equals(opinion.getStatus())) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "当前意见不允许答复");
        }
        OpinionReplyRecord reply = new OpinionReplyRecord();
        reply.setId(opinionMapper.nextReplyId());
        reply.setOpinionId(opinionId);
        reply.setReplyType(command.replyType().name());
        reply.setReason(command.reason());
        reply.setRepliedByEmployeeNo(currentUser.employeeNo());
        reply.setReplyNo(nextReplyNo(opinionId));
        opinionMapper.insertReply(reply);
        updateStatus(opinion, OpinionStatus.PENDING_CONFIRMATION);
        return toView(opinion);
    }

    @Transactional
    public OpinionView confirm(long opinionId, ConfirmOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        requireOpenTask(opinion.getTaskId());
        if (!opinion.getRaisedByEmployeeNo().equals(currentUser.employeeNo())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有意见提出人可以确认答复");
        }
        requireStatus(opinion, OpinionStatus.PENDING_CONFIRMATION, "当前意见不允许确认");
        OpinionReplyRecord reply = opinionMapper.findLatestReply(opinionId);
        if (reply == null) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "意见不存在可确认的答复");
        }
        OpinionConfirmationRecord confirmation = new OpinionConfirmationRecord();
        confirmation.setId(opinionMapper.nextConfirmationId());
        confirmation.setOpinionId(opinionId);
        confirmation.setReplyId(reply.getId());
        confirmation.setPassed(command.passed());
        confirmation.setComment(command.comment());
        confirmation.setConfirmedByEmployeeNo(currentUser.employeeNo());
        opinionMapper.insertConfirmation(confirmation);
        updateStatus(opinion, command.passed() ? OpinionStatus.CONFIRMED_PASS : OpinionStatus.CONFIRMED_REJECTED);
        if (command.passed()) {
            releaseReviewerWhenCurrentStagesCompleted(opinion.getTaskId(), opinion.getRaisedByEmployeeNo(),
                    opinion.getSourceType());
        }
        return toView(opinion);
    }

    @Transactional
    public OpinionView withdraw(long opinionId, WithdrawOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        requireOpenTask(opinion.getTaskId());
        if (!opinion.getRaisedByEmployeeNo().equals(currentUser.employeeNo())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有意见提出人可以撤回");
        }
        if (OpinionStatus.CONFIRMED_PASS.name().equals(opinion.getStatus()) || OpinionStatus.WITHDRAWN.name().equals(opinion.getStatus())) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "已关闭意见不能撤回");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "撤回原因不能为空");
        }
        updateStatus(opinion, OpinionStatus.WITHDRAWN);
        return toView(opinion);
    }

    public List<OpinionView> list(long taskId, OpinionSeverity severity, OpinionStatus status, OpinionSourceType sourceType, String scene, CurrentUser currentUser) {
        return list(taskId, severity, status, sourceType, List.of(), scene, currentUser);
    }

    /**
     * 支持一个阶段同时读取多个意见来源，例如 PCB 第二次设计者答复同时读取 PCB_PROCESS_REVIEW、PCB_STRUCTURE_REVIEW 意见。
     * sourceTypes 有值时优先于兼容参数 sourceType。
     */
    public List<OpinionView> list(long taskId, OpinionSeverity severity, OpinionStatus status, OpinionSourceType sourceType,
                                  List<OpinionSourceType> sourceTypes, String scene, CurrentUser currentUser) {
        requireOpenOrFinishedTaskVisible(taskId, currentUser);
        boolean reviewWorkspace = "REVIEW_WORKSPACE".equalsIgnoreCase(scene);
        List<OpinionSourceType> normalizedSourceTypes = sourceTypes == null ? List.of() : sourceTypes.stream().distinct().toList();
        List<ReviewOpinionRecord> records = opinionMapper.findByTaskId(taskId).stream()
                // 无意见确认是审计记录，不属于需要设计者处理的问题。
                .filter(item -> !item.isNoOpinion())
                .filter(item -> severity == null || severity == item.getSeverity())
                .filter(item -> status == null || status.name().equals(item.getStatus()))
                .filter(item -> normalizedSourceTypes.isEmpty()
                        ? sourceType == null || sourceType == item.getSourceType()
                        : normalizedSourceTypes.contains(item.getSourceType()))
                .filter(item -> !reviewWorkspace || currentUser.employeeNo().equals(item.getRaisedByEmployeeNo()))
                .sorted(java.util.Comparator.comparing(ReviewOpinionRecord::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(ReviewOpinionRecord::getId, java.util.Comparator.reverseOrder())).toList();
        Map<String, UserCenterUserProfileClient.UserProfile> profiles = userProfileClient.getByEmployeeNos(
                records.stream().map(ReviewOpinionRecord::getRaisedByEmployeeNo).toList());
        return records.stream().map(record -> toView(record, profileName(record.getRaisedByEmployeeNo(), profiles))).toList();
    }

    /**
     * @author 王涛
     * @date 2026-09-22
     * @description 在既有筛选、场景权限和倒序规则之上分页返回意见，避免详情页一次加载全部意见及其答复历史。
     */
    public OpinionPage listPage(long taskId, OpinionSeverity severity, OpinionStatus status, OpinionSourceType sourceType, String scene,
                                Integer pageNo, Integer pageSize, CurrentUser currentUser) {
        return listPage(taskId, severity, status, sourceType, List.of(), scene, pageNo, pageSize, currentUser);
    }

    public OpinionPage listPage(long taskId, OpinionSeverity severity, OpinionStatus status, OpinionSourceType sourceType,
                                List<OpinionSourceType> sourceTypes, String scene, Integer pageNo, Integer pageSize,
                                CurrentUser currentUser) {
        int normalizedPageNo = pageNo == null ? 1 : pageNo;
        int normalizedPageSize = pageSize == null ? 20 : pageSize;
        if (normalizedPageNo < 1 || normalizedPageSize < 1 || normalizedPageSize > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "意见分页参数不合法");
        }
        List<OpinionView> all = list(taskId, severity, status, sourceType, sourceTypes, scene, currentUser);
        int fromIndex = (int) Math.min((long) (normalizedPageNo - 1) * normalizedPageSize, all.size());
        int toIndex = Math.min(fromIndex + normalizedPageSize, all.size());
        return new OpinionPage(all.size(), normalizedPageNo, normalizedPageSize, all.subList(fromIndex, toIndex));
    }

    public List<OpinionView> list(long taskId, CurrentUser currentUser) { return list(taskId, null, null, null, "DESIGNER_REPLY", currentUser); }

    public OpinionSummary summary(long taskId, CurrentUser currentUser) {
        return summary(taskId, List.of(), currentUser);
    }

    public OpinionSummary summary(long taskId, List<OpinionSourceType> sourceTypes, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTask(taskId);
        List<OpinionView> opinions = list(taskId, null, null, null, sourceTypes, "DESIGNER_REPLY", currentUser);
        return new OpinionSummary(opinions.size(), count(opinions, OpinionStatus.PENDING_REPLY), count(opinions, OpinionStatus.PENDING_CONFIRMATION),
                count(opinions, OpinionStatus.CONFIRMED_PASS), count(opinions, OpinionStatus.CONFIRMED_REJECTED), count(opinions, OpinionStatus.WITHDRAWN),
                opinions.stream().filter(item -> item.status() == OpinionStatus.PENDING_CONFIRMATION || item.status() == OpinionStatus.CONFIRMED_REJECTED)
                        .map(item -> new OutstandingOpinionView(item.id(), item.comment(), item.raisedByEmployeeNo(), item.raisedByName(), item.status())).toList(),
                unsubmittedReviewers(task, sourceTypes));
    }

    private boolean isNoOpinionSourceAvailable(ReviewTaskRecord task, OpinionSourceType sourceType) {
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        return switch (sourceType) {
            case PCB_REVIEW -> reviewType == ReviewType.PCB && status == TaskStatus.PCB_EXPERT_REVIEWING;
            case SCHEMATIC_REVIEW -> reviewType == ReviewType.SCHEMATIC && status == TaskStatus.SCHEMATIC_REVIEWING;
            case PCB_PROCESS_REVIEW, PCB_STRUCTURE_REVIEW -> reviewType == ReviewType.PCB
                    && status == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING;
            default -> false;
        };
    }

    private boolean reviewerRoleMatchesCurrentSummary(ReviewTaskRecord task, List<OpinionSourceType> sourceTypes, String reviewRole) {
        if (sourceTypes != null && !sourceTypes.isEmpty()) {
            return sourceTypes.stream().anyMatch(source -> switch (source) {
                case PCB_PROCESS_REVIEW -> ReviewRole.PROCESS_EXPERT.name().equals(reviewRole);
                case PCB_STRUCTURE_REVIEW -> ReviewRole.STRUCTURE_EXPERT.name().equals(reviewRole);
                case PCB_REVIEW -> ReviewRole.HARDWARE_EXPERT.name().equals(reviewRole)
                        || ReviewRole.EMC_EXPERT.name().equals(reviewRole) || ReviewRole.PCB_EXPERT.name().equals(reviewRole);
                case SCHEMATIC_REVIEW -> isInitialExpertRole(reviewRole);
                // 互检单人员是流程分配人员，不保存在任务创建时的“业务评审角色—专家”映射中。
                case MUTUAL_CHECK_ITEM, MUTUAL_EXTRA -> false;
            });
        }
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        return switch (status) {
            case PCB_EXPERT_REVIEWING -> isInitialExpertRole(reviewRole);
            case PCB_PROCESS_STRUCTURE_REVIEWING -> ReviewRole.PROCESS_EXPERT.name().equals(reviewRole)
                    || ReviewRole.STRUCTURE_EXPERT.name().equals(reviewRole);
            case SCHEMATIC_REVIEWING -> isInitialExpertRole(reviewRole);
            case MUTUAL_CHECK_REVIEWING -> false;
            default -> false;
        };
    }

    /** 未提交人员由任务创建时保存的“角色—专家”映射推导；旧任务兼容历史组长作为唯一专家。 */
    private List<OutstandingReviewerView> unsubmittedReviewers(ReviewTaskRecord task, List<OpinionSourceType> sourceTypes) {
        List<ReviewOpinionRecord> opinions = opinionMapper.findByTaskId(task.getId());
        List<AssignedReviewer> assignments = new java.util.ArrayList<>();
        for (TaskReviewerAssignment assignment : TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments())) {
            assignment.reviewerEmployeeNos().forEach(employeeNo -> assignments.add(new AssignedReviewer(assignment.reviewRole().name(), employeeNo)));
        }
        if (assignments.isEmpty() && task.getExpertLeaderEmployeeNo() != null && task.getReviewRoles() != null && !task.getReviewRoles().isBlank()) {
            java.util.Arrays.stream(task.getReviewRoles().split(",")).filter(role -> !role.isBlank())
                    .forEach(role -> assignments.add(new AssignedReviewer(role.trim(), task.getExpertLeaderEmployeeNo())));
        }
        Map<String, UserCenterUserProfileClient.UserProfile> profiles = userProfileClient.getByEmployeeNos(
                assignments.stream().map(AssignedReviewer::reviewerEmployeeNo).toList());
        return assignments.stream()
                .filter(assigned -> reviewerRoleMatchesCurrentSummary(task, sourceTypes, assigned.role()))
                .distinct()
                .filter(assigned -> opinions.stream().noneMatch(opinion -> assigned.reviewerEmployeeNo().equals(opinion.getRaisedByEmployeeNo())
                        && matchesSourceForRole(opinion.getSourceType(), assigned.role())))
                .map(assigned -> new OutstandingReviewerView(assigned.reviewerEmployeeNo(),
                        profileName(assigned.reviewerEmployeeNo(), profiles), assigned.role(), "PENDING"))
                .toList();
    }

    private boolean matchesSourceForRole(OpinionSourceType sourceType, String role) {
        return switch (sourceType) {
            case PCB_PROCESS_REVIEW -> ReviewRole.PROCESS_EXPERT.name().equals(role);
            case PCB_STRUCTURE_REVIEW -> ReviewRole.STRUCTURE_EXPERT.name().equals(role);
            case PCB_REVIEW, SCHEMATIC_REVIEW -> isInitialExpertRole(role);
            case MUTUAL_CHECK_ITEM, MUTUAL_EXTRA -> false;
        };
    }

    private boolean isInitialExpertRole(String role) {
        return ReviewRole.HARDWARE_EXPERT.name().equals(role) || ReviewRole.EMC_EXPERT.name().equals(role)
                || ReviewRole.PCB_EXPERT.name().equals(role);
    }

    private record AssignedReviewer(String role, String reviewerEmployeeNo) { }

    private ReviewTaskRecord requireOpenTask(long taskId) {
        ReviewTaskRecord task = requireTask(taskId);
        if (TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许修改意见");
        }
        return task;
    }

    private void requireOpenOrFinishedTaskVisible(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTask(taskId);
        if (!permissionPolicy.canViewAllTasks(currentUser.roles()) && !assignmentAccessMapper.isAssignedToTask(taskId, currentUser.employeeNo())
                && !task.getDesignerEmployeeNo().equals(currentUser.employeeNo())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务的评审意见");
        }
    }

    private ReviewTaskRecord requireTask(long taskId) {
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        }
        return task;
    }

    private ReviewOpinionRecord requireOpinion(long opinionId) {
        ReviewOpinionRecord opinion = opinionMapper.findById(opinionId);
        if (opinion == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审意见不存在");
        }
        return opinion;
    }

    private void requirePendingReplyOwner(ReviewOpinionRecord opinion, CurrentUser currentUser, String action) {
        requireOpinionOwner(opinion, currentUser, action);
        if (!OpinionStatus.PENDING_REPLY.name().equals(opinion.getStatus())) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "意见已被答复，不能" + action);
        }
    }

    private void requireOpinionOwner(ReviewOpinionRecord opinion, CurrentUser currentUser, String action) {
        if (!opinion.getRaisedByEmployeeNo().equals(currentUser.employeeNo()) && !permissionPolicy.isAdministrator(currentUser.roles())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有意见提出人可以" + action + "意见");
        }
    }

    /** 编辑权限依据“是否存在设计者答复”判断，不能把当前状态误当成答复历史。 */
    private void requireNoDesignerReply(long opinionId) {
        if (opinionMapper.existsReply(opinionId)) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "意见已被答复，不能编辑");
        }
    }

    private void updateStatus(ReviewOpinionRecord opinion, OpinionStatus status) {
        opinion.setStatus(status.name());
        if (opinionMapper.updateStatus(opinion) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审意见不存在");
        }
    }

    /**
     * 意见闭环后释放该专家的实时待办。一个人同时承担工艺、结构等多个已开启角色时，
     * 必须对每个角色对应的来源均提交且其意见全部通过，才会从 assigned_reviewer_employee_nos 移除。
     */
    private void releaseReviewerWhenCurrentStagesCompleted(long taskId, String reviewerEmployeeNo, OpinionSourceType completedSource) {
        ReviewTaskRecord task = requireTask(taskId);
        java.util.LinkedHashSet<String> activeReviewerEmployeeNos = new java.util.LinkedHashSet<>(
                TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getAssignedReviewerEmployeeNos()));
        if (!activeReviewerEmployeeNos.contains(reviewerEmployeeNo)) {
            return;
        }
        List<OpinionSourceType> requiredSources = activeSourcesForReviewer(task, reviewerEmployeeNo, completedSource);
        if (requiredSources.isEmpty()) {
            return;
        }
        List<ReviewOpinionRecord> opinions = opinionMapper.findByTaskId(taskId);
        List<ReviewOpinionRecord> safeOpinions = opinions == null ? List.of() : opinions;
        boolean completed = requiredSources.stream().allMatch(source -> {
            List<ReviewOpinionRecord> sourceOpinions = safeOpinions.stream()
                    .filter(item -> reviewerEmployeeNo.equals(item.getRaisedByEmployeeNo()) && source == item.getSourceType())
                    .toList();
            return !sourceOpinions.isEmpty() && sourceOpinions.stream().allMatch(this::isClosedPassedOpinion);
        });
        if (!completed) {
            return;
        }
        activeReviewerEmployeeNos.remove(reviewerEmployeeNo);
        task.setAssignedReviewerEmployeeNos(TaskReviewerAssignmentCodec.encodeReviewerEmployeeNos(activeReviewerEmployeeNos));
        if (taskMapper.updateAssignedReviewerEmployeeNos(task) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
    }

    private List<OpinionSourceType> activeSourcesForReviewer(ReviewTaskRecord task, String reviewerEmployeeNo,
                                                              OpinionSourceType completedSource) {
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        if (status == TaskStatus.MUTUAL_CHECK_REVIEWING) {
            // 互检分配并未保存角色映射；仅在该专家自己提交的互检来源全部闭环后释放待办。
            return completedSource == OpinionSourceType.MUTUAL_CHECK_ITEM || completedSource == OpinionSourceType.MUTUAL_EXTRA
                    ? List.of(completedSource) : List.of();
        }
        if (reviewType == ReviewType.PCB && status == TaskStatus.PCB_EXPERT_REVIEWING
                && hasAssignedRole(task, reviewerEmployeeNo, ReviewRole.PCB_EXPERT, ReviewRole.EMC_EXPERT)) {
            return List.of(OpinionSourceType.PCB_REVIEW);
        }
        if (reviewType == ReviewType.PCB && status == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING) {
            java.util.ArrayList<OpinionSourceType> sources = new java.util.ArrayList<>();
            if (hasStarted(task.getId(), WorkflowAction.START_PCB_PROCESS_REVIEW)
                    && hasAssignedRole(task, reviewerEmployeeNo, ReviewRole.PROCESS_EXPERT)) {
                sources.add(OpinionSourceType.PCB_PROCESS_REVIEW);
            }
            if (hasStarted(task.getId(), WorkflowAction.START_PCB_STRUCTURE_REVIEW)
                    && hasAssignedRole(task, reviewerEmployeeNo, ReviewRole.STRUCTURE_EXPERT)) {
                sources.add(OpinionSourceType.PCB_STRUCTURE_REVIEW);
            }
            return List.copyOf(sources);
        }
        if (reviewType == ReviewType.SCHEMATIC && status == TaskStatus.SCHEMATIC_REVIEWING
                && hasAssignedRole(task, reviewerEmployeeNo, ReviewRole.HARDWARE_EXPERT)) {
            return List.of(OpinionSourceType.SCHEMATIC_REVIEW);
        }
        return List.of();
    }

    private boolean hasAssignedRole(ReviewTaskRecord task, String reviewerEmployeeNo, ReviewRole... roles) {
        java.util.Set<ReviewRole> requiredRoles = java.util.Set.of(roles);
        List<TaskReviewerAssignment> assignments = TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments());
        if (assignments.isEmpty()) {
            return task.getExpertLeaderEmployeeNo() != null && task.getExpertLeaderEmployeeNo().equals(reviewerEmployeeNo);
        }
        return assignments.stream().anyMatch(assignment -> requiredRoles.contains(assignment.reviewRole())
                && assignment.reviewerEmployeeNos().contains(reviewerEmployeeNo));
    }

    private boolean hasStarted(long taskId, WorkflowAction action) {
        List<com.bms.workflow.infrastructure.TaskFlowRecord> records = flowMapper.findByTaskId(taskId);
        return (records == null ? List.<com.bms.workflow.infrastructure.TaskFlowRecord>of() : records).stream()
                .anyMatch(record -> action.name().equals(record.getAction()));
    }

    private boolean isClosedPassedOpinion(ReviewOpinionRecord opinion) {
        return OpinionStatus.CONFIRMED_PASS.name().equals(opinion.getStatus())
                || OpinionStatus.WITHDRAWN.name().equals(opinion.getStatus());
    }

    private int nextReplyNo(long opinionId) {
        OpinionReplyRecord reply = opinionMapper.findLatestReply(opinionId);
        return reply == null ? 1 : reply.getReplyNo() + 1;
    }

    private void requireStatus(ReviewOpinionRecord opinion, OpinionStatus status, String message) {
        if (!status.name().equals(opinion.getStatus())) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, message);
        }
    }

    private String requireComment(String comment) {
        if (comment == null || comment.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "意见内容不能为空");
        }
        return comment;
    }

    private int count(List<OpinionView> opinions, OpinionStatus status) {
        return (int) opinions.stream().filter(opinion -> opinion.status() == status).count();
    }

    private OpinionView toView(ReviewOpinionRecord record) {
        return toView(record, profileName(record.getRaisedByEmployeeNo()));
    }

    private OpinionView toView(ReviewOpinionRecord record, String raisedByName) {
        List<OpinionReplyRecord> replies = opinionMapper.findRepliesByOpinionId(record.getId());
        List<OpinionConfirmationRecord> confirmations = opinionMapper.findConfirmationsByOpinionId(record.getId());
        Map<Long, OpinionConfirmationRecord> confirmationByReplyId = (confirmations == null ? List.<OpinionConfirmationRecord>of() : confirmations).stream()
                .filter(item -> item.getReplyId() != null)
                .collect(Collectors.toMap(OpinionConfirmationRecord::getReplyId, Function.identity(),
                        (left, right) -> left.getId() >= right.getId() ? left : right));
        List<ReplyView> replyViews = (replies == null ? List.<OpinionReplyRecord>of() : replies).stream()
                .map(reply -> ReplyView.from(reply, confirmationByReplyId.get(reply.getId())))
                .toList();
        return OpinionView.from(record, raisedByName, replyViews);
    }

    private String profileName(String employeeNo) {
        return userProfileClient.getByEmployeeNo(employeeNo).displayName();
    }

    private String profileName(String employeeNo, Map<String, UserCenterUserProfileClient.UserProfile> profiles) {
        UserCenterUserProfileClient.UserProfile profile = profiles.get(employeeNo);
        return profile == null ? employeeNo : profile.displayName();
    }

    public record RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String comment, String richText, OpinionSeverity severity) {
        public RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String comment, OpinionSeverity severity) {
            this(taskId, sourceType, sourceItemId, comment, null, severity);
        }
        public RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String comment) {
            this(taskId, sourceType, sourceItemId, comment, null, OpinionSeverity.GENERAL);
        }
    }
    public record UpdateOpinionCommand(String comment, String richText, OpinionSeverity severity) { }
    public record ReplyOpinionCommand(ReplyType replyType, String reason) {
        public ReplyOpinionCommand(ReplyType replyType, String reason, Long ignoredFileVersionId) {
            this(replyType, reason);
        }
    }
    public record ConfirmOpinionCommand(boolean passed, String comment) {
    }
    public record WithdrawOpinionCommand(String reason) {
    }
    public record OpinionView(Long id, Long taskId, OpinionSourceType sourceType, Long sourceItemId, String comment, String richText,
                              String raisedByEmployeeNo, String raisedByName, OpinionSeverity severity, java.time.LocalDateTime createdAt,
                              OpinionStatus status, List<ReplyView> replies) {
        static OpinionView from(ReviewOpinionRecord record, String raisedByName, List<ReplyView> replies) {
            return new OpinionView(record.getId(), record.getTaskId(), record.getSourceType(), record.getSourceItemId(),
                    record.getComment(), record.getRichText(), record.getRaisedByEmployeeNo(), raisedByName, record.getSeverity(), record.getCreatedAt(),
                    OpinionStatus.valueOf(record.getStatus()), replies);
        }
    }

    /** 意见列表分页结果，items 中每项均包含全部答复及其对应确认记录。 */
    public record OpinionPage(long total, int pageNo, int pageSize, List<OpinionView> items) {
        public OpinionPage {
            items = List.copyOf(items);
        }
    }

    /** 一轮设计者答复及其唯一对应的专家确认意见；未确认时 confirmation 为 null。 */
    public record ReplyView(Long id, Integer replyNo, ReplyType replyType, String reason, String repliedByEmployeeNo,
                            java.time.LocalDateTime repliedAt, ConfirmationView confirmation) {
        static ReplyView from(OpinionReplyRecord reply, OpinionConfirmationRecord confirmation) {
            return new ReplyView(reply.getId(), reply.getReplyNo(), ReplyType.valueOf(reply.getReplyType()), reply.getReason(),
                    reply.getRepliedByEmployeeNo(), reply.getCreatedAt(), ConfirmationView.from(confirmation));
        }
    }

    /** 一条确认意见仅归属一轮答复，通过 replyId 与设计者答复一一关联。 */
    public record ConfirmationView(Long id, Boolean passed, String comment, String confirmedByEmployeeNo,
                                   java.time.LocalDateTime confirmedAt) {
        static ConfirmationView from(OpinionConfirmationRecord confirmation) {
            return confirmation == null ? null : new ConfirmationView(confirmation.getId(), confirmation.getPassed(), confirmation.getComment(),
                    confirmation.getConfirmedByEmployeeNo(), confirmation.getCreatedAt());
        }
    }

    public record OpinionSummary(int total, int pendingReply, int pendingConfirmation, int confirmedPass, int confirmedRejected, int withdrawn,
                                 List<OutstandingOpinionView> unconfirmedOpinions, List<OutstandingReviewerView> unsubmittedReviewers) {
    }
    public record OutstandingOpinionView(Long opinionId, String comment, String expertEmployeeNo, String expertName, OpinionStatus status) { }
    public record OutstandingReviewerView(String reviewerEmployeeNo, String reviewerName, String reviewRole, String processStatus) { }

}
