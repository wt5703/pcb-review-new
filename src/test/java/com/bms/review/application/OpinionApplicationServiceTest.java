package com.bms.review.application;

import com.bms.identity.application.CurrentUser;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.identity.application.TaskNodeAuthorizationService;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.domain.OpinionStatus;
import com.bms.review.domain.ReplyType;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.OpinionConfirmationRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.review.infrastructure.TaskCheckItemMapper;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 验证互检额外意见可进入统一闭环，以及设计者答复后由原提出人确认通过的主状态和历史记录写入行为。
 */
class OpinionApplicationServiceTest {
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskCheckItemMapper taskCheckItemMapper = mock(TaskCheckItemMapper.class);
    private final TaskAssignmentAccessMapper assignmentAccessMapper = mock(TaskAssignmentAccessMapper.class);
    private final TaskNodeAuthorizationService taskNodeAuthorizationService = mock(TaskNodeAuthorizationService.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final OpinionApplicationService service = new OpinionApplicationService(opinionMapper, taskMapper, taskCheckItemMapper,
            assignmentAccessMapper, taskNodeAuthorizationService, auditMapper, outboxEventPublisher);

    @Test
    void shouldRaiseMutualExtraOpinion() {
        when(taskMapper.findById(1001L)).thenReturn(task(9L));
        when(opinionMapper.nextOpinionId()).thenReturn(31L);
        CurrentUser mutualReviewer = new CurrentUser(20L, Set.of(Role.PCB_LEADER));

        OpinionApplicationService.OpinionView view = service.raise(new OpinionApplicationService.RaiseOpinionCommand(1001L,
                OpinionSourceType.MUTUAL_EXTRA, null, "补充检查发现的问题"), mutualReviewer);

        assertThat(view.id()).isEqualTo(31L);
        assertThat(view.status()).isEqualTo(OpinionStatus.PENDING_REPLY);
        assertThat(view.raisedByName()).isEqualTo("用户#20");
        verify(opinionMapper).insert(any(ReviewOpinionRecord.class));
    }

    @Test
    void shouldReplyAndConfirmWithoutRestartingReview() {
        ReviewOpinionRecord opinion = opinion(31L, 1001L, 20L, OpinionStatus.PENDING_REPLY);
        when(opinionMapper.findById(31L)).thenReturn(opinion);
        when(taskMapper.findById(1001L)).thenReturn(task(9L));
        when(opinionMapper.nextReplyId()).thenReturn(41L);
        when(opinionMapper.findLatestReply(31L)).thenReturn(null);
        when(opinionMapper.updateStatus(any(ReviewOpinionRecord.class))).thenReturn(1, 1);

        OpinionApplicationService.OpinionView replied = service.reply(31L,
                new OpinionApplicationService.ReplyOpinionCommand(ReplyType.ACCEPT, "已上传新设计文件"),
                new CurrentUser(9L, Set.of(Role.DESIGNER)));

        OpinionReplyRecord persistedReply = new OpinionReplyRecord();
        persistedReply.setId(41L);
        persistedReply.setReplyNo(1);
        when(opinionMapper.findLatestReply(31L)).thenReturn(persistedReply);
        when(opinionMapper.nextConfirmationId()).thenReturn(51L);
        OpinionApplicationService.OpinionView confirmed = service.confirm(31L,
                new OpinionApplicationService.ConfirmOpinionCommand(true, "确认通过"),
                new CurrentUser(20L, Set.of(Role.HARDWARE_EXPERT)));

        assertThat(replied.status()).isEqualTo(OpinionStatus.PENDING_CONFIRMATION);
        assertThat(confirmed.status()).isEqualTo(OpinionStatus.CONFIRMED_PASS);
        verify(opinionMapper).insertReply(any(OpinionReplyRecord.class));
        verify(opinionMapper).insertConfirmation(any());
    }

    @Test
    void shouldSummarizeOpinionsByLifecycleStatus() {
        when(taskMapper.findById(1001L)).thenReturn(task(9L));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(
                opinion(31L, 1001L, 20L, OpinionStatus.PENDING_REPLY),
                opinion(32L, 1001L, 20L, OpinionStatus.CONFIRMED_PASS)));

        OpinionApplicationService.OpinionSummary summary = service.summary(1001L, new CurrentUser(20L, Set.of(Role.HARDWARE_EXPERT)));

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.pendingReply()).isEqualTo(1);
        assertThat(summary.confirmedPass()).isEqualTo(1);
    }

    @Test
    void shouldExposeEveryDesignerReplyWithItsPairedConfirmationWhenListingOpinions() {
        ReviewOpinionRecord opinion = opinion(31L, 1001L, 20L, OpinionStatus.PENDING_CONFIRMATION);
        OpinionReplyRecord reply = new OpinionReplyRecord();
        reply.setId(41L);
        reply.setOpinionId(31L);
        reply.setReplyNo(1);
        reply.setReplyType(ReplyType.ACCEPT.name());
        reply.setReason("已优化布局并更新文件");
        reply.setRepliedBy(9L);
        OpinionConfirmationRecord confirmation = new OpinionConfirmationRecord();
        confirmation.setId(51L);
        confirmation.setOpinionId(31L);
        confirmation.setReplyId(41L);
        confirmation.setPassed(true);
        confirmation.setConfirmedBy(20L);
        OpinionReplyRecord secondReply = new OpinionReplyRecord();
        secondReply.setId(42L);
        secondReply.setOpinionId(31L);
        secondReply.setReplyNo(2);
        secondReply.setReplyType(ReplyType.ACCEPT.name());
        secondReply.setReason("根据退回意见再次调整");
        secondReply.setRepliedBy(9L);
        OpinionConfirmationRecord secondConfirmation = new OpinionConfirmationRecord();
        secondConfirmation.setId(52L);
        secondConfirmation.setOpinionId(31L);
        secondConfirmation.setReplyId(42L);
        secondConfirmation.setPassed(false);
        secondConfirmation.setComment("仍需补充说明");
        secondConfirmation.setConfirmedBy(20L);
        when(taskMapper.findById(1001L)).thenReturn(task(9L));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(opinion));
        when(opinionMapper.findRepliesByOpinionId(31L)).thenReturn(List.of(reply, secondReply));
        when(opinionMapper.findConfirmationsByOpinionId(31L)).thenReturn(List.of(confirmation, secondConfirmation));

        OpinionApplicationService.OpinionView view = service.list(1001L,
                new CurrentUser(20L, Set.of(Role.HARDWARE_EXPERT))).get(0);

        assertThat(view.replies()).hasSize(2);
        assertThat(view.replies().get(0).reason()).isEqualTo("已优化布局并更新文件");
        assertThat(view.replies().get(0).confirmation().passed()).isTrue();
        assertThat(view.replies().get(1).reason()).isEqualTo("根据退回意见再次调整");
        assertThat(view.replies().get(1).confirmation().comment()).isEqualTo("仍需补充说明");
    }

    private ReviewTaskRecord task(long designerId) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setReviewType(ReviewType.PCB.name());
        task.setDesignerId(designerId);
        task.setStatus(TaskStatus.MUTUAL_REVIEWING.name());
        return task;
    }

    private ReviewOpinionRecord opinion(long id, long taskId, long raisedBy, OpinionStatus status) {
        ReviewOpinionRecord opinion = new ReviewOpinionRecord();
        opinion.setId(id);
        opinion.setTaskId(taskId);
        opinion.setSourceType(OpinionSourceType.MUTUAL_EXTRA.name());
        opinion.setContent("问题");
        opinion.setRaisedBy(raisedBy);
        opinion.setStatus(status.name());
        return opinion;
    }
}
