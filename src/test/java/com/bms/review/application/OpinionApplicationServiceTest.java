package com.bms.review.application;

import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.bms.task.application.TaskProcessorAuthorizationService;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.domain.OpinionSeverity;
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
import com.bms.workflow.infrastructure.TaskFlowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

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
    private final TaskProcessorAuthorizationService taskProcessorAuthorizationService = mock(TaskProcessorAuthorizationService.class);
    private final TaskFlowMapper flowMapper = mock(TaskFlowMapper.class);
    private final UserCenterUserProfileClient userProfileClient = mock(UserCenterUserProfileClient.class);
    private final OpinionApplicationService service = new OpinionApplicationService(opinionMapper, taskMapper, taskCheckItemMapper,
            assignmentAccessMapper, taskProcessorAuthorizationService, flowMapper, userProfileClient);

    @BeforeEach
    void mockUserProfiles() {
        when(userProfileClient.getByEmployeeNo(any())).thenAnswer(invocation -> {
            String employeeNo = invocation.getArgument(0);
            return new UserCenterUserProfileClient.UserProfile(employeeNo, employeeNo, null, null, null);
        });
    }

    @Test
    void shouldRaiseMutualExtraOpinion() {
        when(taskMapper.findById(1001L)).thenReturn(task("BMS009"));
        when(opinionMapper.nextOpinionId()).thenReturn(31L);

        OpinionApplicationService.OpinionView view = service.raise(new OpinionApplicationService.RaiseOpinionCommand(1001L,
                OpinionSourceType.MUTUAL_EXTRA, null, "补充检查发现的问题", "<p>补充检查发现的问题</p>", OpinionSeverity.GENERAL), "BMS020", "BMS020");

        assertThat(view.id()).isEqualTo(31L);
        assertThat(view.status()).isEqualTo(OpinionStatus.PENDING_REPLY);
        assertThat(view.raisedByName()).isEqualTo("BMS020");
        ArgumentCaptor<ReviewOpinionRecord> recordCaptor = ArgumentCaptor.forClass(ReviewOpinionRecord.class);
        verify(opinionMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getComment()).isEqualTo("补充检查发现的问题");
        assertThat(recordCaptor.getValue().getRichText()).isEqualTo("<p>补充检查发现的问题</p>");
    }

    @Test
    void shouldReplyAndConfirmWithoutRestartingReview() {
        ReviewOpinionRecord opinion = opinion(31L, 1001L, "BMS020", OpinionStatus.PENDING_REPLY);
        when(opinionMapper.findById(31L)).thenReturn(opinion);
        when(taskMapper.findById(1001L)).thenReturn(task("BMS009"));
        when(opinionMapper.nextReplyId()).thenReturn(41L);
        when(opinionMapper.findLatestReply(31L)).thenReturn(null);
        when(opinionMapper.updateStatus(any(ReviewOpinionRecord.class))).thenReturn(1, 1);

        OpinionApplicationService.OpinionView replied = service.reply(31L,
                new OpinionApplicationService.ReplyOpinionCommand(ReplyType.ACCEPT, "已上传新设计文件"),
                "BMS009", "BMS009");

        OpinionReplyRecord persistedReply = new OpinionReplyRecord();
        persistedReply.setId(41L);
        persistedReply.setReplyNo(1);
        when(opinionMapper.findLatestReply(31L)).thenReturn(persistedReply);
        when(opinionMapper.nextConfirmationId()).thenReturn(51L);
        OpinionApplicationService.OpinionView confirmed = service.confirm(31L,
                new OpinionApplicationService.ConfirmOpinionCommand(true, "确认通过"),
                "BMS020", "BMS020");

        assertThat(replied.status()).isEqualTo(OpinionStatus.PENDING_CONFIRMATION);
        assertThat(confirmed.status()).isEqualTo(OpinionStatus.CONFIRMED_PASS);
        verify(opinionMapper).insertReply(any(OpinionReplyRecord.class));
        verify(opinionMapper).insertConfirmation(any());
    }

    @Test
    void shouldRemoveReviewerFromActiveAssignmentsOnlyAfterConfirmingSubmission() {
        ReviewTaskRecord activeTask = task("BMS009");
        activeTask.setStatus(TaskStatus.PCB_EXPERT_REVIEWING.name());
        activeTask.setReviewerAssignments("PCB_EXPERT:BMS020");
        activeTask.setAssignedReviewerEmployeeNos("BMS020");
        ReviewOpinionRecord opinion = opinion(31L, 1001L, "BMS020", OpinionStatus.PENDING_CONFIRMATION);
        opinion.setSourceType(OpinionSourceType.PCB_REVIEW);
        OpinionReplyRecord reply = new OpinionReplyRecord();
        reply.setId(41L);
        when(taskMapper.findById(1001L)).thenReturn(activeTask);
        when(opinionMapper.findById(31L)).thenReturn(opinion);
        when(opinionMapper.findLatestReply(31L)).thenReturn(reply);
        when(opinionMapper.nextConfirmationId()).thenReturn(51L);
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(opinion));
        when(opinionMapper.updateStatus(any())).thenReturn(1);
        when(taskMapper.updateAssignedReviewerEmployeeNos(any())).thenReturn(1);

        service.confirm(31L, new OpinionApplicationService.ConfirmOpinionCommand(true, "确认通过"),
                "BMS020", "BMS020");

        assertThat(activeTask.getAssignedReviewerEmployeeNos()).isEqualTo("BMS020");

        service.submitNoOpinion(1001L, OpinionSourceType.PCB_REVIEW, "BMS020", "BMS020");

        assertThat(activeTask.getAssignedReviewerEmployeeNos()).isEmpty();
        verify(taskMapper).updateAssignedReviewerEmployeeNos(activeTask);
    }

    @Test
    void shouldRejectSubmissionWhenReviewerHasUnconfirmedOpinion() {
        ReviewTaskRecord activeTask = task("BMS009");
        activeTask.setStatus(TaskStatus.PCB_EXPERT_REVIEWING.name());
        activeTask.setReviewerAssignments("PCB_EXPERT:BMS020");
        activeTask.setAssignedReviewerEmployeeNos("BMS020");
        ReviewOpinionRecord opinion = opinion(31L, 1001L, "BMS020", OpinionStatus.PENDING_CONFIRMATION);
        opinion.setSourceType(OpinionSourceType.PCB_REVIEW);
        when(taskMapper.findById(1001L)).thenReturn(activeTask);
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(opinion));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submitNoOpinion(1001L,
                        OpinionSourceType.PCB_REVIEW, "BMS020", "BMS020"))
                .isInstanceOf(com.bms.common.BusinessException.class)
                .hasMessage("仍存在未答复、未确认或未确认通过的评审意见，不能确认提交");
    }

    @Test
    void shouldSummarizeOpinionsByLifecycleStatus() {
        when(assignmentAccessMapper.isAssignedToTask(1001L, "BMS020")).thenReturn(true);
        when(taskMapper.findById(1001L)).thenReturn(task("BMS009"));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(
                opinion(31L, 1001L, "BMS020", OpinionStatus.PENDING_REPLY),
                opinion(32L, 1001L, "BMS020", OpinionStatus.CONFIRMED_PASS),
                opinion(33L, 1001L, "BMS020", OpinionStatus.CONFIRMED_PASS)));

        OpinionApplicationService.OpinionSummary summary = service.summary(1001L, "BMS020", "BMS020");

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.pendingReply()).isEqualTo(1);
        assertThat(summary.confirmedPass()).isEqualTo(2);
    }

    @Test
    void shouldExposeEveryDesignerReplyWithItsPairedConfirmationWhenListingOpinions() {
        when(assignmentAccessMapper.isAssignedToTask(1001L, "BMS020")).thenReturn(true);
        ReviewOpinionRecord opinion = opinion(31L, 1001L, "BMS020", OpinionStatus.PENDING_CONFIRMATION);
        OpinionReplyRecord reply = new OpinionReplyRecord();
        reply.setId(41L);
        reply.setOpinionId(31L);
        reply.setReplyNo(1);
        reply.setReplyType(ReplyType.ACCEPT.name());
        reply.setReason("已优化布局并更新文件");
        reply.setRepliedByEmployeeNo("BMS009");
        OpinionConfirmationRecord confirmation = new OpinionConfirmationRecord();
        confirmation.setId(51L);
        confirmation.setOpinionId(31L);
        confirmation.setReplyId(41L);
        confirmation.setPassed(true);
        confirmation.setConfirmedByEmployeeNo("BMS020");
        OpinionReplyRecord secondReply = new OpinionReplyRecord();
        secondReply.setId(42L);
        secondReply.setOpinionId(31L);
        secondReply.setReplyNo(2);
        secondReply.setReplyType(ReplyType.ACCEPT.name());
        secondReply.setReason("根据退回意见再次调整");
        secondReply.setRepliedByEmployeeNo("BMS009");
        OpinionConfirmationRecord secondConfirmation = new OpinionConfirmationRecord();
        secondConfirmation.setId(52L);
        secondConfirmation.setOpinionId(31L);
        secondConfirmation.setReplyId(42L);
        secondConfirmation.setPassed(false);
        secondConfirmation.setComment("仍需补充说明");
        secondConfirmation.setConfirmedByEmployeeNo("BMS020");
        when(taskMapper.findById(1001L)).thenReturn(task("BMS009"));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(opinion));
        when(opinionMapper.findRepliesByOpinionId(31L)).thenReturn(List.of(reply, secondReply));
        when(opinionMapper.findConfirmationsByOpinionId(31L)).thenReturn(List.of(confirmation, secondConfirmation));

        OpinionApplicationService.OpinionView view = service.list(1001L,
                "BMS020", "BMS020").get(0);

        assertThat(view.replies()).hasSize(2);
        assertThat(view.replies().get(0).reason()).isEqualTo("已优化布局并更新文件");
        assertThat(view.replies().get(0).confirmation().passed()).isTrue();
        assertThat(view.replies().get(1).reason()).isEqualTo("根据退回意见再次调整");
        assertThat(view.replies().get(1).confirmation().comment()).isEqualTo("仍需补充说明");
    }

    private ReviewTaskRecord task(String designerEmployeeNo) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setReviewType(ReviewType.PCB.name());
        task.setDesignerEmployeeNo(designerEmployeeNo);
        task.setStatus(TaskStatus.MUTUAL_CHECK_REVIEWING.name());
        return task;
    }

    private ReviewOpinionRecord opinion(long id, long taskId, String raisedByEmployeeNo, OpinionStatus status) {
        ReviewOpinionRecord opinion = new ReviewOpinionRecord();
        opinion.setId(id);
        opinion.setTaskId(taskId);
        opinion.setSourceType(OpinionSourceType.MUTUAL_EXTRA);
        opinion.setComment("问题");
        opinion.setRaisedByEmployeeNo(raisedByEmployeeNo);
        opinion.setStatus(status.name());
        return opinion;
    }
}
