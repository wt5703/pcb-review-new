package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.TaskNodeAuthorizationService;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.review.domain.OpinionSourceType;
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
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
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
    private final TaskNodeAuthorizationService taskNodeAuthorizationService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    @Autowired
    public OpinionApplicationService(ReviewOpinionMapper opinionMapper, ReviewTaskMapper taskMapper, TaskCheckItemMapper taskCheckItemMapper,
                                     TaskAssignmentAccessMapper assignmentAccessMapper, TaskNodeAuthorizationService taskNodeAuthorizationService,
                                     OutboxEventPublisher outboxEventPublisher) {
        this.opinionMapper = opinionMapper;
        this.taskMapper = taskMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.taskNodeAuthorizationService = taskNodeAuthorizationService;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * @author 王涛
     * @date 2026-09-18
     * @description 兼容既有单元测试的构造入口；运行时由 Spring 注入完整依赖以支持未提交专家汇总。
     */
    @Transactional
    public OpinionView raise(RaiseOpinionCommand command, CurrentUser currentUser) {
        ReviewTaskRecord task = requireOpenTask(command.taskId());
        taskNodeAuthorizationService.requireCurrentTaskProcessor(command.taskId(), currentUser);
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
        record.setSourceType(command.sourceType().name());
        record.setSourceItemId(command.sourceItemId());
        record.setSeverity(command.severity() == null || command.severity().isBlank() ? "GENERAL" : command.severity());
        String richText = requireContent(command.richText() == null ? command.content() : command.richText());
        record.setContent(richText);
        record.setRichText(richText);
        record.setRaisedBy(currentUser.id());
        record.setRaisedByName(currentUser.resolvedDisplayName());
        record.setStatus(OpinionStatus.PENDING_REPLY.name());
        opinionMapper.insert(record);
        outboxEventPublisher.publishTaskEvent("OPINION_RAISED", record.getTaskId(), currentUser.id());
        return toView(record);
    }

    /**
     * 评审人确认当前阶段没有意见。该操作不触发工作流，而是持久化一条已通过的
     * PASS 意见记录，使“已提交无意见”与实际评审意见一样可审计、可追溯。
     */
    @Transactional
    public OpinionView submitNoOpinion(long taskId, OpinionSourceType sourceType, CurrentUser currentUser) {
        ReviewTaskRecord task = requireOpenTask(taskId);
        taskNodeAuthorizationService.requireCurrentTaskProcessor(taskId, currentUser);
        if (!isNoOpinionSourceAvailable(task, sourceType)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前评审节点不支持提交无意见");
        }
        boolean alreadySubmitted = opinionMapper.findByTaskId(taskId).stream()
                .anyMatch(item -> currentUser.id().equals(item.getRaisedBy()) && sourceType.name().equals(item.getSourceType()));
        if (alreadySubmitted) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "当前阶段已提交评审意见，不能重复提交无意见");
        }
        ReviewOpinionRecord record = new ReviewOpinionRecord();
        record.setId(opinionMapper.nextOpinionId());
        record.setTaskId(taskId);
        record.setSourceType(sourceType.name());
        record.setSeverity("PASS");
        record.setContent("无意见，确认提交");
        record.setRichText("无意见，确认提交");
        record.setRaisedBy(currentUser.id());
        record.setRaisedByName(currentUser.resolvedDisplayName());
        record.setStatus(OpinionStatus.CONFIRMED_PASS.name());
        opinionMapper.insert(record);
        outboxEventPublisher.publishTaskEvent("NO_OPINION_SUBMITTED", taskId, currentUser.id());
        return toView(record);
    }

    /** 编辑仅限尚未被设计者答复的本人意见，防止改变已进入闭环的评审事实。 */
    @Transactional
    public OpinionView update(long opinionId, UpdateOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        requireOpenTask(opinion.getTaskId());
        requirePendingReplyOwner(opinion, currentUser, "编辑");
        String richText = requireContent(command.richText() == null ? command.content() : command.richText());
        opinion.setSeverity(command.severity() == null || command.severity().isBlank() ? opinion.getSeverity() : command.severity());
        opinion.setContent(richText);
        opinion.setRichText(richText);
        if (opinionMapper.updateContent(opinion) != 1) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "意见已被答复，不能编辑");
        }
        outboxEventPublisher.publishTaskEvent("OPINION_UPDATED", opinion.getTaskId(), currentUser.id());
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
        outboxEventPublisher.publishTaskEvent("OPINION_DELETED", opinion.getTaskId(), currentUser.id());
    }

    @Transactional
    public OpinionView reply(long opinionId, ReplyOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        ReviewTaskRecord task = requireOpenTask(opinion.getTaskId());
        if (!task.getDesignerId().equals(currentUser.id()) && !permissionPolicy.isAdministrator(currentUser.roles())) {
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
        reply.setRepliedBy(currentUser.id());
        reply.setReplyNo(nextReplyNo(opinionId));
        opinionMapper.insertReply(reply);
        updateStatus(opinion, OpinionStatus.PENDING_CONFIRMATION);
        outboxEventPublisher.publishTaskEvent("OPINION_REPLIED", opinion.getTaskId(), currentUser.id());
        return toView(opinion);
    }

    @Transactional
    public OpinionView confirm(long opinionId, ConfirmOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        requireOpenTask(opinion.getTaskId());
        if (!opinion.getRaisedBy().equals(currentUser.id())) {
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
        confirmation.setConfirmedBy(currentUser.id());
        opinionMapper.insertConfirmation(confirmation);
        updateStatus(opinion, command.passed() ? OpinionStatus.CONFIRMED_PASS : OpinionStatus.CONFIRMED_REJECTED);
        outboxEventPublisher.publishTaskEvent(command.passed() ? "OPINION_CONFIRMED_PASS" : "OPINION_CONFIRMED_REJECTED",
                opinion.getTaskId(), currentUser.id());
        return toView(opinion);
    }

    @Transactional
    public OpinionView withdraw(long opinionId, WithdrawOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        requireOpenTask(opinion.getTaskId());
        if (!opinion.getRaisedBy().equals(currentUser.id())) {
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

    public List<OpinionView> list(long taskId, String severity, OpinionStatus status, OpinionSourceType sourceType, String scene, CurrentUser currentUser) {
        return list(taskId, severity, status, sourceType, List.of(), scene, currentUser);
    }

    /**
     * 支持一个阶段同时读取多个意见来源，例如 PCB 第二次设计者答复同时读取工艺、结构评审意见。
     * sourceTypes 有值时优先于兼容参数 sourceType。
     */
    public List<OpinionView> list(long taskId, String severity, OpinionStatus status, OpinionSourceType sourceType,
                                  List<OpinionSourceType> sourceTypes, String scene, CurrentUser currentUser) {
        requireOpenOrFinishedTaskVisible(taskId, currentUser);
        boolean reviewWorkspace = "REVIEW_WORKSPACE".equalsIgnoreCase(scene);
        List<OpinionSourceType> normalizedSourceTypes = sourceTypes == null ? List.of() : sourceTypes.stream().distinct().toList();
        return opinionMapper.findByTaskId(taskId).stream()
                // severity=PASS 是“无意见，确认提交”的审计记录，不属于需要设计者处理的问题。
                .filter(item -> !"PASS".equalsIgnoreCase(item.getSeverity()))
                .filter(item -> severity == null || severity.isBlank() || severity.trim().equalsIgnoreCase(item.getSeverity()))
                .filter(item -> status == null || status.name().equals(item.getStatus()))
                .filter(item -> normalizedSourceTypes.isEmpty()
                        ? sourceType == null || sourceType.name().equals(item.getSourceType())
                        : normalizedSourceTypes.stream().anyMatch(source -> source.name().equals(item.getSourceType())))
                .filter(item -> !reviewWorkspace || currentUser.id().equals(item.getRaisedBy()))
                .sorted(java.util.Comparator.comparing(ReviewOpinionRecord::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(ReviewOpinionRecord::getId, java.util.Comparator.reverseOrder()))
                .map(this::toView).toList();
    }

    /**
     * @author 王涛
     * @date 2026-09-22
     * @description 在既有筛选、场景权限和倒序规则之上分页返回意见，避免详情页一次加载全部意见及其答复历史。
     */
    public OpinionPage listPage(long taskId, String severity, OpinionStatus status, OpinionSourceType sourceType, String scene,
                                Integer pageNo, Integer pageSize, CurrentUser currentUser) {
        return listPage(taskId, severity, status, sourceType, List.of(), scene, pageNo, pageSize, currentUser);
    }

    public OpinionPage listPage(long taskId, String severity, OpinionStatus status, OpinionSourceType sourceType,
                                List<OpinionSourceType> sourceTypes, String scene, Integer pageNo, Integer pageSize,
                                CurrentUser currentUser) {
        int normalizedPageNo = pageNo == null ? 1 : pageNo;
        int normalizedPageSize = pageSize == null ? 20 : pageSize;
        if (normalizedPageNo < 1 || normalizedPageSize < 1 || normalizedPageSize > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "意见分页参数不合法");
        }
        List<OpinionView> all = list(taskId, severity, status, sourceType, sourceTypes, scene, currentUser);
        int fromIndex = Math.min((normalizedPageNo - 1) * normalizedPageSize, all.size());
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
                        .map(item -> new OutstandingOpinionView(item.id(), item.content(), item.raisedBy(), item.raisedByName(), item.status())).toList(),
                unsubmittedReviewers(task, sourceTypes));
    }

    private boolean isNoOpinionSourceAvailable(ReviewTaskRecord task, OpinionSourceType sourceType) {
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        return switch (sourceType) {
            case EXPERT_REVIEW -> reviewType == ReviewType.PCB && status == TaskStatus.PCB_EXPERT_REVIEWING;
            case SCHEMATIC_REVIEW -> reviewType == ReviewType.SCHEMATIC && status == TaskStatus.SCHEMATIC_REVIEWING;
            case PROCESS_REVIEW, STRUCTURE_REVIEW -> reviewType == ReviewType.PCB
                    && status == TaskStatus.PCB_PROCESS_STRUCTURE_REVIEWING;
            default -> false;
        };
    }

    private boolean reviewerRoleMatchesCurrentSummary(ReviewTaskRecord task, List<OpinionSourceType> sourceTypes, String reviewRole) {
        if (sourceTypes != null && !sourceTypes.isEmpty()) {
            return sourceTypes.stream().anyMatch(source -> switch (source) {
                case PROCESS_REVIEW -> ReviewRole.PROCESS_EXPERT.name().equals(reviewRole);
                case STRUCTURE_REVIEW -> ReviewRole.STRUCTURE_EXPERT.name().equals(reviewRole);
                case EXPERT_REVIEW -> ReviewRole.HARDWARE_EXPERT.name().equals(reviewRole)
                        || ReviewRole.EMC_EXPERT.name().equals(reviewRole) || ReviewRole.PCB_EXPERT.name().equals(reviewRole);
                case SCHEMATIC_REVIEW -> ReviewRole.SCHEMATIC_HARDWARE_EXPERT.name().equals(reviewRole)
                        || ReviewRole.SCHEMATIC_OTHER_EXPERT.name().equals(reviewRole);
                case MUTUAL_CHECK_ITEM, MUTUAL_EXTRA -> ReviewRole.PCB_MUTUAL_CHECK.name().equals(reviewRole)
                        || ReviewRole.SCHEMATIC_MUTUAL_CHECK.name().equals(reviewRole);
            });
        }
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        return switch (status) {
            case PCB_EXPERT_REVIEWING -> ReviewRole.HARDWARE_EXPERT.name().equals(reviewRole)
                    || ReviewRole.EMC_EXPERT.name().equals(reviewRole) || ReviewRole.PCB_EXPERT.name().equals(reviewRole);
            case PCB_PROCESS_STRUCTURE_REVIEWING -> ReviewRole.PROCESS_EXPERT.name().equals(reviewRole)
                    || ReviewRole.STRUCTURE_EXPERT.name().equals(reviewRole);
            case SCHEMATIC_REVIEWING -> ReviewRole.SCHEMATIC_HARDWARE_EXPERT.name().equals(reviewRole)
                    || ReviewRole.SCHEMATIC_OTHER_EXPERT.name().equals(reviewRole);
            case MUTUAL_CHECK_REVIEWING -> ReviewRole.PCB_MUTUAL_CHECK.name().equals(reviewRole)
                    || ReviewRole.SCHEMATIC_MUTUAL_CHECK.name().equals(reviewRole);
            default -> false;
        };
    }

    /**
     * 未提交人员仅由任务创建时指定的专家/组长和任务评审职责推导；某人提交对应来源的意见（含 PASS 无意见）即视为已提交。
     * 不维护独立人员状态或流程分配快照。
     */
    private List<OutstandingReviewerView> unsubmittedReviewers(ReviewTaskRecord task, List<OpinionSourceType> sourceTypes) {
        List<ReviewOpinionRecord> opinions = opinionMapper.findByTaskId(task.getId());
        List<AssignedReviewer> assignments = new java.util.ArrayList<>();
        if (task.getExpertLeaderId() != null && task.getReviewRoles() != null && !task.getReviewRoles().isBlank()) {
            java.util.Arrays.stream(task.getReviewRoles().split(",")).filter(role -> !role.isBlank())
                    .forEach(role -> assignments.add(new AssignedReviewer(role.trim(), task.getExpertLeaderId())));
        }
        return assignments.stream()
                .filter(assigned -> reviewerRoleMatchesCurrentSummary(task, sourceTypes, assigned.role()))
                .distinct()
                .filter(assigned -> opinions.stream().noneMatch(opinion -> assigned.reviewerId().equals(opinion.getRaisedBy())
                        && matchesSourceForRole(opinion.getSourceType(), assigned.role())))
                .map(assigned -> new OutstandingReviewerView(assigned.reviewerId(), "专家#" + assigned.reviewerId(), assigned.role(), "PENDING"))
                .toList();
    }

    private boolean matchesSourceForRole(String sourceType, String role) {
        return switch (sourceType) {
            case "PROCESS_REVIEW" -> ReviewRole.PROCESS_EXPERT.name().equals(role);
            case "STRUCTURE_REVIEW" -> ReviewRole.STRUCTURE_EXPERT.name().equals(role);
            case "EXPERT_REVIEW" -> ReviewRole.HARDWARE_EXPERT.name().equals(role) || ReviewRole.EMC_EXPERT.name().equals(role)
                    || ReviewRole.PCB_EXPERT.name().equals(role);
            case "SCHEMATIC_REVIEW" -> ReviewRole.SCHEMATIC_HARDWARE_EXPERT.name().equals(role)
                    || ReviewRole.SCHEMATIC_OTHER_EXPERT.name().equals(role);
            case "MUTUAL_CHECK_ITEM", "MUTUAL_EXTRA" -> ReviewRole.PCB_MUTUAL_CHECK.name().equals(role)
                    || ReviewRole.SCHEMATIC_MUTUAL_CHECK.name().equals(role);
            default -> false;
        };
    }

    private record AssignedReviewer(String role, Long reviewerId) { }

    private ReviewTaskRecord requireOpenTask(long taskId) {
        ReviewTaskRecord task = requireTask(taskId);
        if (TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许修改意见");
        }
        return task;
    }

    private void requireOpenOrFinishedTaskVisible(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTask(taskId);
        if (!permissionPolicy.canViewAllTasks(currentUser.roles()) && !assignmentAccessMapper.isAssignedToTask(taskId, currentUser.id())
                && !task.getDesignerId().equals(currentUser.id())) {
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
        if (!opinion.getRaisedBy().equals(currentUser.id()) && !permissionPolicy.isAdministrator(currentUser.roles())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有意见提出人可以" + action + "意见");
        }
        if (!OpinionStatus.PENDING_REPLY.name().equals(opinion.getStatus())) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "意见已被答复，不能" + action);
        }
    }

    private void updateStatus(ReviewOpinionRecord opinion, OpinionStatus status) {
        opinion.setStatus(status.name());
        if (opinionMapper.updateStatus(opinion) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审意见不存在");
        }
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

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "意见内容不能为空");
        }
        return content;
    }

    private int count(List<OpinionView> opinions, OpinionStatus status) {
        return (int) opinions.stream().filter(opinion -> opinion.status() == status).count();
    }

    private OpinionView toView(ReviewOpinionRecord record) {
        List<OpinionReplyRecord> replies = opinionMapper.findRepliesByOpinionId(record.getId());
        List<OpinionConfirmationRecord> confirmations = opinionMapper.findConfirmationsByOpinionId(record.getId());
        Map<Long, OpinionConfirmationRecord> confirmationByReplyId = (confirmations == null ? List.<OpinionConfirmationRecord>of() : confirmations).stream()
                .filter(item -> item.getReplyId() != null)
                .collect(Collectors.toMap(OpinionConfirmationRecord::getReplyId, Function.identity(),
                        (left, right) -> left.getId() >= right.getId() ? left : right));
        List<ReplyView> replyViews = (replies == null ? List.<OpinionReplyRecord>of() : replies).stream()
                .map(reply -> ReplyView.from(reply, confirmationByReplyId.get(reply.getId())))
                .toList();
        return OpinionView.from(record, replyViews);
    }

    public record RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content, String richText, String severity) {
        public RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content, String severity) {
            this(taskId, sourceType, sourceItemId, content, null, severity);
        }
        public RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content) {
            this(taskId, sourceType, sourceItemId, content, null, "GENERAL");
        }
    }
    public record UpdateOpinionCommand(String content, String richText, String severity) { }
    public record ReplyOpinionCommand(ReplyType replyType, String reason) {
        public ReplyOpinionCommand(ReplyType replyType, String reason, Long ignoredFileVersionId) {
            this(replyType, reason);
        }
    }
    public record ConfirmOpinionCommand(boolean passed, String comment) {
    }
    public record WithdrawOpinionCommand(String reason) {
    }
    public record OpinionView(Long id, Long taskId, OpinionSourceType sourceType, Long sourceItemId, String content, String richText,
                              Long raisedBy, String raisedByName, String severity, java.time.LocalDateTime createdAt,
                              OpinionStatus status, List<ReplyView> replies) {
        static OpinionView from(ReviewOpinionRecord record, List<ReplyView> replies) {
            return new OpinionView(record.getId(), record.getTaskId(), OpinionSourceType.valueOf(record.getSourceType()), record.getSourceItemId(),
                    record.getContent(), record.getRichText(), record.getRaisedBy(), record.getRaisedByName(), record.getSeverity(), record.getCreatedAt(),
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
    public record ReplyView(Long id, Integer replyNo, ReplyType replyType, String reason, Long repliedBy,
                            java.time.LocalDateTime repliedAt, ConfirmationView confirmation) {
        static ReplyView from(OpinionReplyRecord reply, OpinionConfirmationRecord confirmation) {
            return new ReplyView(reply.getId(), reply.getReplyNo(), ReplyType.valueOf(reply.getReplyType()), reply.getReason(),
                    reply.getRepliedBy(), reply.getCreatedAt(), ConfirmationView.from(confirmation));
        }
    }

    /** 一条确认意见仅归属一轮答复，通过 replyId 与设计者答复一一关联。 */
    public record ConfirmationView(Long id, Boolean passed, String comment, Long confirmedBy,
                                   java.time.LocalDateTime confirmedAt) {
        static ConfirmationView from(OpinionConfirmationRecord confirmation) {
            return confirmation == null ? null : new ConfirmationView(confirmation.getId(), confirmation.getPassed(), confirmation.getComment(),
                    confirmation.getConfirmedBy(), confirmation.getCreatedAt());
        }
    }

    public record OpinionSummary(int total, int pendingReply, int pendingConfirmation, int confirmedPass, int confirmedRejected, int withdrawn,
                                 List<OutstandingOpinionView> unconfirmedOpinions, List<OutstandingReviewerView> unsubmittedReviewers) {
    }
    public record OutstandingOpinionView(Long opinionId, String content, Long expertId, String expertName, OpinionStatus status) { }
    public record OutstandingReviewerView(Long reviewerId, String reviewerName, String reviewRole, String processStatus) { }

}
