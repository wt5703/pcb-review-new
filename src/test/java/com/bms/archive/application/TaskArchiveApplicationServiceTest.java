package com.bms.archive.application;

import com.bms.archive.infrastructure.TaskArchiveSnapshotMapper;
import com.bms.archive.infrastructure.TaskArchiveSnapshotRecord;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.notification.infrastructure.NotificationSendRecord;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
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
 * @description 验证任务结束时冻结阶段文件、邮件和 task_flow_record 的流程节点，归档读取仅使用冻结快照。
 */
class TaskArchiveApplicationServiceTest {
    private final TaskArchiveSnapshotMapper snapshotMapper = mock(TaskArchiveSnapshotMapper.class);
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final TaskFlowMapper flowMapper = mock(TaskFlowMapper.class);
    private final NotificationSendRecordMapper notificationSendRecordMapper = mock(NotificationSendRecordMapper.class);
    private final TaskArchiveApplicationService service = new TaskArchiveApplicationService(snapshotMapper, fileMapper,
            flowMapper, notificationSendRecordMapper, new ObjectMapper().findAndRegisterModules());

    @Test
    void shouldFreezeStructuredArchiveFieldsForDisplay() {
        LocalDateTime time = LocalDateTime.of(2026, 9, 18, 10, 30);
        ReviewFileRecord file = new ReviewFileRecord();
        file.setId(101L);
        file.setTaskId(1001L);
        file.setFileCategory("PCB_REVIEW");
        file.setFileName("BMU_Control_V2.PCB");
        file.setUploadedBy(9L);
        file.setUploadedAt(time);
        file.setUploadedStage("PCB_EXPERT_REVIEWING");
        TaskFlowRecord flow = new TaskFlowRecord();
        flow.setId(401L);
        flow.setAction("START_MUTUAL_CHECK");
        flow.setActionName("开启互检单评审");
        flow.setOperateId(2L);
        flow.setComment("互检人员已完成分配");
        flow.setCreatedAt(time.plusHours(1));
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setTaskName("BMU 控制板评审");
        task.setStatus("FINISHED");
        when(fileMapper.findLatestByTaskId(1001L)).thenReturn(List.of(file));
        when(flowMapper.findByTaskId(1001L)).thenReturn(List.of(flow));
        when(notificationSendRecordMapper.findByTaskId(1001L)).thenReturn(List.of(new NotificationSendRecord(501L,
                "REVIEW_MAIL", "expert@example.com", "TASK_CREATED", "SENT", null, time.plusHours(2))));
        service.archive(task);

        ArgumentCaptor<TaskArchiveSnapshotRecord> captured = ArgumentCaptor.forClass(TaskArchiveSnapshotRecord.class);
        verify(snapshotMapper).insert(captured.capture());
        when(snapshotMapper.findByTaskId(1001L)).thenReturn(captured.getValue());
        TaskArchiveApplicationService.ArchiveView view = service.get(1001L);

        assertThat(view.flowNodes()).singleElement().satisfies(value -> {
            assertThat(value.stageName()).isEqualTo("开启互检单评审");
            assertThat(value.operatorName()).isEqualTo("用户#2");
            assertThat(value.content()).isEqualTo("互检人员已完成分配");
        });
        assertThat(view.stageFiles()).singleElement().satisfies(value -> {
            assertThat(value.stageName()).isEqualTo("专家评审");
            assertThat(value.fileName()).isEqualTo("BMU_Control_V2.PCB");
            assertThat(value.downloadPath()).isEqualTo("/leapmotor/pcb_review/files/download?fileId=101");
        });
        assertThat(view.notificationRecords()).singleElement().satisfies(value -> {
            assertThat(value.recipient()).isEqualTo("expert@example.com");
            assertThat(value.deliveryStatus()).isEqualTo("SENT");
        });
        verify(flowMapper).findByTaskId(1001L);
    }
}
