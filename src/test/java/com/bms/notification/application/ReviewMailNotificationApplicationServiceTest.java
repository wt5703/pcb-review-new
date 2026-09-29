package com.bms.notification.application;

import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.review.infrastructure.ReviewerWhitelistMapper;
import com.bms.review.infrastructure.ReviewerWhitelistRecord;
import com.bms.notification.domain.MailMessage;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import com.bms.review.domain.ReviewRole;
import com.bms.task.domain.ReviewTask;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 验证评审节点邮件的收件角色筛选、中文正文与附件选择，避免把工艺/结构通知误发到 PCB 首轮专家邮件中。
 */
class ReviewMailNotificationApplicationServiceTest {
    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final ReviewerWhitelistMapper whitelistMapper = mock(ReviewerWhitelistMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReviewMailNotificationApplicationService service = new ReviewMailNotificationApplicationService(outboxEventMapper,
            fileMapper, whitelistMapper, objectMapper, "bms-hardware-development@bms.example.com");

    @Test
    void shouldNotifyOnlyInitialPcbExpertsWhenTaskSubmitted() throws Exception {
        when(whitelistMapper.findById(1L)).thenReturn(user(1L, "王鹏飞", "wang@bms.example.com"));
        when(whitelistMapper.findById(2L)).thenReturn(user(2L, "刘满红", "liu@bms.example.com"));
        when(whitelistMapper.findById(3L)).thenReturn(user(3L, "章俊", "zhang@bms.example.com"));
        when(fileMapper.findById(101L)).thenReturn(file(101L, "初版PCB.zip", "PCB_REVIEW"));
        ReviewTask task = ReviewTask.draft(10L, ReviewType.PCB, "任务", "BMS", 9L, "设计者", "P1", "BMU", LocalDate.now(),
                9L, "组长", List.of(ReviewRole.HARDWARE_EXPERT, ReviewRole.PCB_EXPERT, ReviewRole.PROCESS_EXPERT),
                List.of(new TaskReviewerAssignment(ReviewRole.HARDWARE_EXPERT, List.of(1L)),
                        new TaskReviewerAssignment(ReviewRole.PCB_EXPERT, List.of(2L)),
                        new TaskReviewerAssignment(ReviewRole.PROCESS_EXPERT, List.of(3L))), null);
        task.addInitialFile(101L);

        service.enqueueTaskCreated(task);

        MailMessage message = payload();
        assertThat(message.notificationType()).isEqualTo("PCB_TASK_CREATED");
        assertThat(message.subject()).isEqualTo("BMSP1");
        assertThat(message.content()).contains("王工，刘工你们好", "附件为BMSPCB文件，请进行硬件评审、PCB评审");
        assertThat(message.recipients()).extracting(MailMessage.MailRecipient::email)
                .containsExactly("wang@bms.example.com", "liu@bms.example.com");
        assertThat(message.carbonCopies()).extracting(MailMessage.MailRecipient::name).containsExactly("BMS硬件开发部");
        assertThat(message.attachments()).extracting(MailMessage.MailAttachment::fileName).containsExactly("初版PCB.zip");
    }

    @Test
    void shouldAttachOnlyProcessAndStructureFilesWhenOpeningTheirReviews() throws Exception {
        when(whitelistMapper.findById(3L)).thenReturn(user(3L, "章俊", "zhang@bms.example.com"));
        when(whitelistMapper.findById(4L)).thenReturn(user(4L, "陈远杰", "chen@bms.example.com"));
        when(fileMapper.findLatestByTaskIdAndCategory(20L, "PCB_PROCESS_REVIEW")).thenReturn(List.of(file(201L, "工艺.zip", "PCB_PROCESS_REVIEW")));
        when(fileMapper.findLatestByTaskIdAndCategory(20L, "PCB_STRUCTURE_REVIEW")).thenReturn(List.of(file(202L, "结构.zip", "PCB_STRUCTURE_REVIEW")));
        ReviewTaskRecord task = task(20L, "PCB", "BMS", "PROCESS_EXPERT:3;STRUCTURE_EXPERT:4");

        service.enqueuePcbStageReview(task, List.of(com.bms.file.domain.FileCategory.PCB_PROCESS_REVIEW,
                com.bms.file.domain.FileCategory.PCB_STRUCTURE_REVIEW));

        MailMessage message = payload();
        assertThat(message.subject()).isEqualTo("BMS工艺与结构评审");
        assertThat(message.content()).contains("附件为BMS工艺与结构评审文件，请进行工艺与结构评审");
        assertThat(message.attachments()).extracting(MailMessage.MailAttachment::fileName).containsExactly("工艺.zip", "结构.zip");
    }

    @Test
    void shouldNotifyOnlyStructureExpertsWhenOnlyStructureReviewIsOpened() throws Exception {
        when(whitelistMapper.findById(4L)).thenReturn(user(4L, "陈远杰", "chen@bms.example.com"));
        when(fileMapper.findLatestByTaskIdAndCategory(20L, "PCB_STRUCTURE_REVIEW"))
                .thenReturn(List.of(file(202L, "结构.zip", "PCB_STRUCTURE_REVIEW")));
        ReviewTaskRecord task = task(20L, "PCB", "BMS", "PROCESS_EXPERT:3;STRUCTURE_EXPERT:4");

        service.enqueuePcbStageReview(task, List.of(com.bms.file.domain.FileCategory.PCB_STRUCTURE_REVIEW));

        MailMessage message = payload();
        assertThat(message.subject()).isEqualTo("BMS结构评审");
        assertThat(message.recipients()).extracting(MailMessage.MailRecipient::email).containsExactly("chen@bms.example.com");
        assertThat(message.attachments()).extracting(MailMessage.MailAttachment::fileName).containsExactly("结构.zip");
    }

    @Test
    void shouldNotifyAllSchematicExpertsWithAllLatestFilesWhenFinished() throws Exception {
        when(whitelistMapper.findById(1L)).thenReturn(user(1L, "王鹏飞", "wang@bms.example.com"));
        when(whitelistMapper.findById(2L)).thenReturn(user(2L, "刘满红", "liu@bms.example.com"));
        for (com.bms.file.domain.FileCategory category : com.bms.file.domain.FileCategory.values()) {
            when(fileMapper.findLatestByTaskIdAndCategory(30L, category.name())).thenReturn(List.of(file(300L + category.ordinal(), category.name() + ".zip", category.name())));
        }
        service.enqueueTaskFinished(task(30L, "SCHEMATIC", "VCU", "HARDWARE_EXPERT:1;EMC_EXPERT:2"));

        MailMessage message = payload();
        assertThat(message.notificationType()).isEqualTo("SCHEMATIC_TASK_FINISHED");
        assertThat(message.subject()).isEqualTo("VCU原理图评审终版图");
        assertThat(message.content()).contains("以下是VCU原理图评审终版图");
        assertThat(message.recipients()).hasSize(2);
        assertThat(message.attachments()).hasSize(4);
    }

    private MailMessage payload() throws Exception {
        ArgumentCaptor<OutboxEventRecord> captor = ArgumentCaptor.forClass(OutboxEventRecord.class);
        verify(outboxEventMapper).insert(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(ReviewMailNotificationApplicationService.REVIEW_MAIL_EVENT);
        return objectMapper.readValue(captor.getValue().payload(), MailMessage.class);
    }

    private ReviewerWhitelistRecord user(long id, String name, String email) {
        ReviewerWhitelistRecord record = new ReviewerWhitelistRecord();
        record.setId(id); record.setDisplayName(name); record.setEmail(email);
        return record;
    }

    private ReviewFileRecord file(long id, String fileName, String category) {
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(id); record.setFileId("file-" + id); record.setFileName(fileName); record.setFileCategory(category); record.setResourcePath("/resource/" + id);
        return record;
    }

    private ReviewTaskRecord task(long id, String reviewType, String projectName, String reviewerAssignments) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(id); task.setReviewType(reviewType); task.setProjectName(projectName); task.setReviewerAssignments(reviewerAssignments);
        task.setReviewRoles(reviewerAssignments.substring(0, reviewerAssignments.indexOf(':'))); task.setExpertLeaderId(9L);
        return task;
    }
}
