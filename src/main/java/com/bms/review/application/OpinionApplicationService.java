package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.TaskNodeAuthorizationService;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.domain.OpinionStatus;
import com.bms.review.domain.ReplyType;
import com.bms.review.infrastructure.OpinionConfirmationRecord;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.review.infrastructure.TaskCheckItemMapper;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
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
    private final TaskReviewerMapper reviewerMapper;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final TaskNodeAuthorizationService taskNodeAuthorizationService;
    private final OperationAuditMapper auditMapper;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    @Autowired
    public OpinionApplicationService(ReviewOpinionMapper opinionMapper, ReviewTaskMapper taskMapper, TaskCheckItemMapper taskCheckItemMapper, TaskReviewerMapper reviewerMapper,
                                     TaskAssignmentAccessMapper assignmentAccessMapper, TaskNodeAuthorizationService taskNodeAuthorizationService,
                                     OperationAuditMapper auditMapper, OutboxEventPublisher outboxEventPublisher) {
        this.opinionMapper = opinionMapper;
        this.taskMapper = taskMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.reviewerMapper = reviewerMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.taskNodeAuthorizationService = taskNodeAuthorizationService;
        this.auditMapper = auditMapper;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * @author 王涛
     * @date 2026-09-18
     * @description 兼容既有单元测试的构造入口；运行时由 Spring 注入完整依赖以支持未提交专家汇总。
     */
    public OpinionApplicationService(ReviewOpinionMapper opinionMapper, ReviewTaskMapper taskMapper, TaskCheckItemMapper taskCheckItemMapper,
                                     TaskAssignmentAccessMapper assignmentAccessMapper, TaskNodeAuthorizationService taskNodeAuthorizationService,
                                     OperationAuditMapper auditMapper, OutboxEventPublisher outboxEventPublisher) {
        this(opinionMapper, taskMapper, taskCheckItemMapper, null, assignmentAccessMapper, taskNodeAuthorizationService, auditMapper, outboxEventPublisher);
    }

    @Transactional
    public OpinionView raise(RaiseOpinionCommand command, CurrentUser currentUser) {
        requireOpenTask(command.taskId());
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
        appendAudit(record, "OPINION_RAISED", currentUser.id());
        outboxEventPublisher.publishTaskEvent("OPINION_RAISED", record.getTaskId(), currentUser.id());
        return toView(record);
    }

    @Transactional
    public OpinionView reply(long opinionId, ReplyOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        ReviewTaskRecord task = requireOpenTask(opinion.getTaskId());
        if (!task.getDesignerId().equals(currentUser.id())) {
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
        appendAudit(opinion, "OPINION_REPLIED", currentUser.id());
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
        appendAudit(opinion, command.passed() ? "OPINION_CONFIRMED_PASS" : "OPINION_CONFIRMED_REJECTED", currentUser.id());
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
        appendAudit(opinion, "OPINION_WITHDRAWN", currentUser.id());
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
        List<OpinionView> opinions = list(taskId, null, null, null, sourceTypes, "DESIGNER_REPLY", currentUser);
        return new OpinionSummary(opinions.size(), count(opinions, OpinionStatus.PENDING_REPLY), count(opinions, OpinionStatus.PENDING_CONFIRMATION),
                count(opinions, OpinionStatus.CONFIRMED_PASS), count(opinions, OpinionStatus.CONFIRMED_REJECTED), count(opinions, OpinionStatus.WITHDRAWN),
                opinions.stream().filter(item -> item.status() == OpinionStatus.PENDING_CONFIRMATION || item.status() == OpinionStatus.CONFIRMED_REJECTED)
                        .map(item -> new OutstandingOpinionView(item.id(), item.content(), item.raisedBy(), item.raisedByName(), item.status())).toList(),
                (reviewerMapper == null ? List.<TaskReviewerRecord>of() : reviewerMapper.findActiveByTaskId(taskId)).stream()
                        .filter(item -> "PENDING".equals(item.getProcessStatus()) || "IN_PROGRESS".equals(item.getProcessStatus()))
                        .map(item -> new OutstandingReviewerView(item.getReviewerId(), "专家#" + item.getReviewerId(), item.getReviewRole(), item.getProcessStatus())).toList());
    }

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

    private void appendAudit(ReviewOpinionRecord opinion, String action, long operatorId) {
        if (auditMapper != null) {
            auditMapper.insert(new OperationAuditRecord("REVIEW_OPINION", opinion.getId(), action, operatorId, opinion.getStatus()));
        }
    }

    public record RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content, String richText, String severity) {
        public RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content, String severity) {
            this(taskId, sourceType, sourceItemId, content, null, severity);
        }
        public RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content) {
            this(taskId, sourceType, sourceItemId, content, null, "GENERAL");
        }
    }
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
