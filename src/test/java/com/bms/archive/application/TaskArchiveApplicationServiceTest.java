package com.bms.archive.application;

import com.bms.archive.infrastructure.TaskArchiveSnapshotMapper;
import com.bms.archive.infrastructure.TaskArchiveSnapshotRecord;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.notification.infrastructure.NotificationSendRecord;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证任务结束时归档会冻结专家意见及设计者答复、当前阶段的最新文件和邮件投递记录，供归档页面按图片要求直接展示。
 */
class TaskArchiveApplicationServiceTest {
    private final TaskArchiveSnapshotMapper snapshotMapper = mock(TaskArchiveSnapshotMapper.class);
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final TaskReviewerMapper reviewerMapper = mock(TaskReviewerMapper.class);
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final TaskFlowMapper flowMapper = mock(TaskFlowMapper.class);
    private final NotificationSendRecordMapper sendRecordMapper = mock(NotificationSendRecordMapper.class);
    private final TaskArchiveApplicationService service = new TaskArchiveApplicationService(snapshotMapper, fileMapper, reviewerMapper,
            opinionMapper, flowMapper, sendRecordMapper, new ObjectMapper().findAndRegisterModules());

    @Test
    void shouldFreezeStructuredArchiveFieldsForDisplay() {
        LocalDateTime time = LocalDateTime.of(2026, 9, 18, 10, 30);
        ReviewFileRecord file = new ReviewFileRecord();
        file.setId(101L);
        file.setFileCategory("PCB_SCHEMATIC");
        file.setBusinessFileKey("MAIN_PCB");
        file.setFileName("BMU_Control_V2.PCB");
        file.setVersionNo(2);
        file.setUploadedBy(9L);
        file.setUploadedAt(time);
        file.setUploadedStage("PCB_EXPERT_REVIEWING");
        ReviewOpinionRecord opinion = new ReviewOpinionRecord();
        opinion.setId(201L);
        opinion.setSourceType(OpinionSourceType.EXPERT_REVIEW.name());
        opinion.setContent("电源区走线间距不足");
        opinion.setRaisedBy(20L);
        opinion.setCreatedAt(time);
        OpinionReplyRecord reply = new OpinionReplyRecord();
        reply.setId(301L);
        reply.setReplyNo(1);
        reply.setReplyType("ACCEPT");
        reply.setReason("已调整为安全间距");
        reply.setRepliedBy(9L);
        reply.setCreatedAt(time.plusMinutes(10));
        TaskFlowRecord flow = new TaskFlowRecord();
        flow.setId(401L);
        flow.setToStatus("MUTUAL_REVIEWING");
        flow.setAction("START_MUTUAL_CHECK");
        flow.setOperatorId(2L);
        flow.setComment(null);
        flow.setCreatedAt(time.plusHours(1));
        TaskReviewerRecord reviewer = new TaskReviewerRecord();
        reviewer.setReviewerId(20L);
        reviewer.setReviewRole("PCB_EXPERT");
        reviewer.setProcessStatus("COMPLETED");
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setTaskName("BMU 控制板评审");
        task.setStatus("FINISHED");
        when(fileMapper.findLatestByTaskId(1001L)).thenReturn(List.of(file));
        when(reviewerMapper.findActiveByTaskId(1001L)).thenReturn(List.of(reviewer));
        when(opinionMapper.findByTaskId(1001L)).thenReturn(List.of(opinion));
        when(opinionMapper.findRepliesByOpinionId(201L)).thenReturn(List.of(reply));
        when(flowMapper.findByTaskId(1001L)).thenReturn(List.of(flow));
        when(sendRecordMapper.findByTaskId(1001L)).thenReturn(List.of(
                new NotificationSendRecord(501L, "OPINION_RAISED", "expert@bms.com", "OPINION_RAISED", "SUCCESS", null, time)));

        service.archive(task);

        ArgumentCaptor<TaskArchiveSnapshotRecord> captured = ArgumentCaptor.forClass(TaskArchiveSnapshotRecord.class);
        verify(snapshotMapper).insert(captured.capture());
        when(snapshotMapper.findByTaskId(1001L)).thenReturn(captured.getValue());
        TaskArchiveApplicationService.ArchiveView view = service.get(1001L);

        assertThat(view.flowOpinions()).singleElement().satisfies(value -> {
            assertThat(value.stageName()).isEqualTo("专家评审");
            assertThat(value.designerReplies()).singleElement().satisfies(answer -> assertThat(answer.content()).isEqualTo("已调整为安全间距"));
        });
        assertThat(view.stageFiles()).singleElement().satisfies(value -> {
            assertThat(value.stageName()).isEqualTo("专家评审");
            assertThat(value.fileName()).isEqualTo("BMU_Control_V2.PCB");
            assertThat(value.downloadPath()).isEqualTo("/api/v1/files/101/download");
        });
        assertThat(view.mailRecords()).singleElement().satisfies(value -> {
            assertThat(value.sentAt()).isEqualTo(time);
            assertThat(value.scenario()).isEqualTo("专家意见待答复通知");
            assertThat(value.status()).isEqualTo("已发送");
        });
    }
}
