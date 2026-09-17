package com.leapmotor.pcbreview.review.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.application.TaskNodeAuthorizationService;
import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.review.domain.OpinionSourceType;
import com.leapmotor.pcbreview.review.domain.OpinionStatus;
import com.leapmotor.pcbreview.review.domain.ReplyType;
import com.leapmotor.pcbreview.review.infrastructure.OpinionConfirmationRecord;
import com.leapmotor.pcbreview.review.infrastructure.OpinionReplyRecord;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionMapper;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionRecord;
import com.leapmotor.pcbreview.review.infrastructure.TaskCheckItemMapper;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public OpinionApplicationService(ReviewOpinionMapper opinionMapper, ReviewTaskMapper taskMapper, TaskCheckItemMapper taskCheckItemMapper,
                                     TaskAssignmentAccessMapper assignmentAccessMapper, TaskNodeAuthorizationService taskNodeAuthorizationService) {
        this.opinionMapper = opinionMapper;
        this.taskMapper = taskMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.taskNodeAuthorizationService = taskNodeAuthorizationService;
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
        record.setSeverity("UNSPECIFIED");
        record.setContent(requireContent(command.content()));
        record.setRaisedBy(currentUser.id());
        record.setFileVersionId(command.fileVersionId());
        record.setStatus(OpinionStatus.PENDING_REPLY.name());
        record.setVersion(0L);
        opinionMapper.insert(record);
        return OpinionView.from(record);
    }

    @Transactional
    public OpinionView reply(long opinionId, ReplyOpinionCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = requireOpinion(opinionId);
        ReviewTaskRecord task = requireOpenTask(opinion.getTaskId());
        if (!task.getDesignerId().equals(currentUser.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有任务设计者可以答复意见");
        }
        requireStatus(opinion, OpinionStatus.PENDING_REPLY, "当前意见不允许答复");
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
        return OpinionView.from(opinion);
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
        updateStatus(opinion, command.passed() ? OpinionStatus.CONFIRMED_PASS : OpinionStatus.PENDING_REPLY);
        return OpinionView.from(opinion);
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
        return OpinionView.from(opinion);
    }

    public List<OpinionView> list(long taskId, CurrentUser currentUser) {
        requireOpenOrFinishedTaskVisible(taskId, currentUser);
        return opinionMapper.findByTaskId(taskId).stream().map(OpinionView::from).toList();
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

    public record RaiseOpinionCommand(long taskId, OpinionSourceType sourceType, Long sourceItemId, String content, Long fileVersionId) {
    }
    public record ReplyOpinionCommand(ReplyType replyType, String reason, Long fileVersionId) {
    }
    public record ConfirmOpinionCommand(boolean passed, String comment) {
    }
    public record WithdrawOpinionCommand(String reason) {
    }
    public record OpinionView(Long id, Long taskId, OpinionSourceType sourceType, Long sourceItemId, String content,
                              Long raisedBy, Long fileVersionId, OpinionStatus status, long version) {
        static OpinionView from(ReviewOpinionRecord record) {
            return new OpinionView(record.getId(), record.getTaskId(), OpinionSourceType.valueOf(record.getSourceType()), record.getSourceItemId(),
                    record.getContent(), record.getRaisedBy(), record.getFileVersionId(), OpinionStatus.valueOf(record.getStatus()), record.getVersion());
        }
    }
}
