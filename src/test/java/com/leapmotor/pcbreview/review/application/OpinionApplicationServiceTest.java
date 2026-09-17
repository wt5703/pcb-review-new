package com.leapmotor.pcbreview.review.application;

import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.application.TaskNodeAuthorizationService;
import com.leapmotor.pcbreview.identity.domain.Role;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.review.domain.OpinionSourceType;
import com.leapmotor.pcbreview.review.domain.OpinionStatus;
import com.leapmotor.pcbreview.review.domain.ReplyType;
import com.leapmotor.pcbreview.review.infrastructure.OpinionReplyRecord;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionMapper;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionRecord;
import com.leapmotor.pcbreview.review.infrastructure.TaskCheckItemMapper;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
    private final OpinionApplicationService service = new OpinionApplicationService(opinionMapper, taskMapper, taskCheckItemMapper,
            assignmentAccessMapper, taskNodeAuthorizationService);

    @Test
    void shouldRaiseMutualExtraOpinion() {
        when(taskMapper.findById(1001L)).thenReturn(task(9L));
        when(opinionMapper.nextOpinionId()).thenReturn(31L);
        CurrentUser mutualReviewer = new CurrentUser(20L, Set.of(Role.PCB_LEADER));

        OpinionApplicationService.OpinionView view = service.raise(new OpinionApplicationService.RaiseOpinionCommand(1001L,
                OpinionSourceType.MUTUAL_EXTRA, null, "补充检查发现的问题", 101L), mutualReviewer);

        assertThat(view.id()).isEqualTo(31L);
        assertThat(view.status()).isEqualTo(OpinionStatus.PENDING_REPLY);
        verify(opinionMapper).insert(any(ReviewOpinionRecord.class));
    }

    @Test
    void shouldReplyAndConfirmWithoutRestartingReview() {
        ReviewOpinionRecord opinion = opinion(31L, 1001L, 20L, OpinionStatus.PENDING_REPLY, 0L);
        when(opinionMapper.findById(31L)).thenReturn(opinion);
        when(taskMapper.findById(1001L)).thenReturn(task(9L));
        when(opinionMapper.nextReplyId()).thenReturn(41L);
        when(opinionMapper.findLatestReply(31L)).thenReturn(null);
        when(opinionMapper.updateStatus(any(ReviewOpinionRecord.class))).thenReturn(1, 1);

        OpinionApplicationService.OpinionView replied = service.reply(31L,
                new OpinionApplicationService.ReplyOpinionCommand(ReplyType.ACCEPT, "已上传新设计文件", 102L),
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

    private ReviewTaskRecord task(long designerId) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setReviewType(ReviewType.PCB.name());
        task.setDesignerId(designerId);
        task.setStatus(TaskStatus.MUTUAL_REVIEWING.name());
        return task;
    }

    private ReviewOpinionRecord opinion(long id, long taskId, long raisedBy, OpinionStatus status, long version) {
        ReviewOpinionRecord opinion = new ReviewOpinionRecord();
        opinion.setId(id);
        opinion.setTaskId(taskId);
        opinion.setSourceType(OpinionSourceType.MUTUAL_EXTRA.name());
        opinion.setContent("问题");
        opinion.setRaisedBy(raisedBy);
        opinion.setStatus(status.name());
        opinion.setVersion(version);
        return opinion;
    }
}
