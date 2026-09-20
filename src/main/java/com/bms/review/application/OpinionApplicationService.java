package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.file.domain.FileCategory;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
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
import com.bms.review.infrastructure.OpinionAttachmentMapper;
import com.bms.review.infrastructure.OpinionAttachmentRecord;
import com.bms.review.infrastructure.OpinionAttachmentViewRecord;
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
import java.util.LinkedHashSet;

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
    private final ReviewFileMapper fileMapper;
    private final OpinionAttachmentMapper opinionAttachmentMapper;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final TaskNodeAuthorizationService taskNodeAuthorizationService;
    private final OperationAuditMapper auditMapper;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    @Autowired
    public OpinionApplicationService(ReviewOpinionMapper opinionMapper, ReviewTaskMapper taskMapper, TaskCheckItemMapper taskCheckItemMapper, TaskReviewerMapper reviewerMapper,
                                     ReviewFileMapper fileMapper, OpinionAttachmentMapper opinionAttachmentMapper,
                                     TaskAssignmentAccessMapper assignmentAccessMapper, TaskNodeAuthorizationService taskNodeAuthorizationService,
                                     OperationAuditMapper auditMapper, OutboxEventPublisher outboxEventPublisher) {
        this.opinionMapper = opinionMapper;
        this.taskMapper = taskMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.reviewerMapper = reviewerMapper;
        this.fileMapper = fileMapper;
        this.opinionAttachmentMapper = opinionAttachmentMapper;
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
        this(opinionMapper, taskMapper, taskCheckItemMapper, null, null, null, assignmentAccessMapper, taskNodeAuthorizationService, auditMapper, outboxEventPublisher);
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
        record.setContent(requireContent(command.content()));
        record.setRaisedBy(currentUser.id());
        record.setFileVersionId(command.fileVersionId());
        record.setImageUrl(command.imageUrl());
        record.setStatus(OpinionStatus.PENDING_REPLY.name());
        record.setVersion(0L);
        opinionMapper.insert(record);
        attachImages(command.taskId(), record.getId(), command.attachmentFileIds());
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
        reply.setFileVersionId(command.fileVersionId());
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

    public List<OpinionView> list(long taskId, String severity, OpinionStatus status, CurrentUser currentUser) {
        requireOpenOrFinishedTaskVisible(taskId, currentUser);
        return opinionMapper.findByTaskId(taskId).stream()
                .filter(item -> severity == null || severity.isBlank() || severity.trim().equalsIgnoreCase(item.getSeverity()))
                .filter(item -> status == null || status.name().equals(item.getStatus()))
                .map(this::toView).toList();
    }

    public List<OpinionView> list(long taskId, CurrentUser currentUser) { return list(taskId, null, null, currentUser); }

    public OpinionSummary summary(long taskId, CurrentUser currentUser) {
        List<OpinionView> opinions = list(taskId, currentUser);
        return new OpinionSummary(opinions.size(), count(opinions, OpinionStatus.PENDING_REPLY), count(opinions, OpinionStatus.PENDING_CONFIRMATION),
                count(opinions, OpinionStatus.CONFIRMED_PASS), count(opinions, OpinionStatus.WITHDRAWN),
                opinions.stream().filter(item -> item.status() == OpinionStatus.PENDING_CONFIRMATION || item.status() == OpinionStatus.CONFIRMED_REJECTED)
                        .map(item -> new OutstandingOpinionView(item.id(), item.content(), item.raisedBy(), "专家#" + item.raisedBy(), item.status())).toList(),
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
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "评审意见已被其他操作更新，请刷新后重试");
        }
        opinion.setVersion(opinion.getVersion() + 1);
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
        List<ReplyView> replies = opinionMapper.findRepliesByOpinionId(record.getId()).stream()
                .map(ReplyView::from).toList();
        List<ConfirmationView> confirmations = opinionMapper.findConfirmationsByOpinionId(record.getId()).stream()
                .map(ConfirmationView::from).toList();
        List<OpinionAttachmentView> attachments = opinionAttachmentMapper == null ? List.of() : opinionAttachmentMapper.findByOpinionId(record.getId()).stream()
                .map(OpinionAttachmentView::from).toList();
        return OpinionView.from(record, replies, confirmations, attachments);
    }

    private void attachImages(long taskId, long opinionId, List<Long> attachmentFileIds) {
        if (attachmentFileIds == null || attachmentFileIds.isEmpty()) {
            return;
        }
        if (fileMapper == null || opinionAttachmentMapper == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "意见图片服务尚未初始化");
        }
        int sortNo = 1;
        for (Long fileId : new LinkedHashSet<>(attachmentFileIds)) {
            if (fileId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "意见图片文件 ID 不能为空");
            }
            ReviewFileRecord file = fileMapper.findById(fileId);
            if (file == null || !Long.valueOf(taskId).equals(file.getTaskId())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "意见图片不存在或不属于当前任务");
            }
            if (!FileCategory.OPINION_ATTACHMENT.name().equals(file.getFileCategory())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "意见图片必须使用 OPINION_ATTACHMENT 类别上传");
            }
            opinionAttachmentMapper.insert(new OpinionAttachmentRecord(opinionId, fileId, sortNo++));
        }
    }

    private void appendAudit(ReviewOpinionRecord opinion, String action, long operatorId) {
        if (auditMapper != null) {
            auditMapper.insert(new OperationAuditRecord("REVIEW_OPINION", opinion.getId(), action, operatorId, opinion.getStatus()));
        }
    }

    public record RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content, Long fileVersionId, String severity, String imageUrl,
                                      List<Long> attachmentFileIds) {
        public RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content, Long fileVersionId) {
            this(taskId, sourceType, sourceItemId, content, fileVersionId, "GENERAL", null, List.of());
        }
    }
    public record ReplyOpinionCommand(ReplyType replyType, String reason, Long fileVersionId) {
    }
    public record ConfirmOpinionCommand(boolean passed, String comment) {
    }
    public record WithdrawOpinionCommand(String reason) {
    }
    public record OpinionView(Long id, Long taskId, OpinionSourceType sourceType, Long sourceItemId, String content,
                              Long raisedBy, Long fileVersionId, String imageUrl, OpinionStatus status, long version,
                              List<ReplyView> designerReplies, List<ConfirmationView> confirmations, List<OpinionAttachmentView> attachments) {
        static OpinionView from(ReviewOpinionRecord record, List<ReplyView> designerReplies, List<ConfirmationView> confirmations,
                                List<OpinionAttachmentView> attachments) {
            return new OpinionView(record.getId(), record.getTaskId(), OpinionSourceType.valueOf(record.getSourceType()), record.getSourceItemId(),
                    record.getContent(), record.getRaisedBy(), record.getFileVersionId(), record.getImageUrl(), OpinionStatus.valueOf(record.getStatus()), record.getVersion(),
                    designerReplies, confirmations, attachments);
        }
    }

    public record OpinionAttachmentView(Long fileId, Integer sortNo, String fileName, String fileCategory, String previewUrl) {
        static OpinionAttachmentView from(OpinionAttachmentViewRecord record) {
            return new OpinionAttachmentView(record.getFileId(), record.getSortNo(), record.getFileName(), record.getFileCategory(), record.getPreviewUrl());
        }
    }

    public record ReplyView(Long id, Long opinionId, int replyNo, ReplyType replyType, String reason, Long fileVersionId,
                            Long repliedBy, java.time.LocalDateTime repliedAt) {
        static ReplyView from(OpinionReplyRecord record) {
            return new ReplyView(record.getId(), record.getOpinionId(), record.getReplyNo(), ReplyType.valueOf(record.getReplyType()), record.getReason(),
                    record.getFileVersionId(), record.getRepliedBy(), record.getCreatedAt());
        }
    }

    public record ConfirmationView(Long id, Long replyId, boolean passed, String comment, Long confirmedBy,
                                   java.time.LocalDateTime confirmedAt) {
        static ConfirmationView from(OpinionConfirmationRecord record) {
            return new ConfirmationView(record.getId(), record.getReplyId(), record.getPassed(), record.getComment(),
                    record.getConfirmedBy(), record.getCreatedAt());
        }
    }

    public record OpinionSummary(int total, int pendingReply, int pendingConfirmation, int confirmedPass, int withdrawn,
                                 List<OutstandingOpinionView> unconfirmedOpinions, List<OutstandingReviewerView> unsubmittedReviewers) {
    }
    public record OutstandingOpinionView(Long opinionId, String content, Long expertId, String expertName, OpinionStatus status) { }
    public record OutstandingReviewerView(Long reviewerId, String reviewerName, String reviewRole, String processStatus) { }
}
