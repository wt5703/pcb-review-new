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
            fileMapper, whitelistMapper, objectMapper, new MailTemplateService());

    @org.junit.jupiter.api.BeforeEach
    void hardwareDepartmentMembers() {
        when(whitelistMapper.findActiveByDepartmentName("BMS硬件开发部")).thenReturn(List.of(
                user(101L, "H1", "硬件成员", "hardware@example.com"),
                user(102L, "H2", "重复邮箱", " HARDWARE@example.com "),
                user(103L, "H3", "无邮箱", " ")));
    }

    @Test
    void shouldNotifyOnlyPcbAndEmcExpertsWhenTaskSubmitted() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS001")).thenReturn(user(1L, "BMS001", "王鹏飞", "wang@bms.example.com"));
        when(whitelistMapper.findActiveByEmployeeNo("BMS002")).thenReturn(user(2L, "BMS002", "刘满红", "liu@bms.example.com"));
        when(whitelistMapper.findActiveByEmployeeNo("BMS003")).thenReturn(user(3L, "BMS003", "章俊", "zhang@bms.example.com"));
        when(fileMapper.findById(101L)).thenReturn(file(101L, "初版PCB.zip", "PCB_REVIEW"));
        ReviewTask task = ReviewTask.draft(10L, ReviewType.PCB, "任务", "BMS", "BMS009", "设计者", "P1", "BMU", LocalDate.now(),
                "BMS009", "组长", List.of(ReviewRole.PCB_EXPERT, ReviewRole.EMC_EXPERT, ReviewRole.PROCESS_EXPERT),
                List.of(new TaskReviewerAssignment(ReviewRole.PCB_EXPERT, List.of("BMS001")),
                        new TaskReviewerAssignment(ReviewRole.EMC_EXPERT, List.of("BMS002")),
                        new TaskReviewerAssignment(ReviewRole.PROCESS_EXPERT, List.of("BMS003"))), null);
        task.addInitialFile(101L);

        service.enqueueTaskCreated(task);

        MailMessage message = payload();
        assertThat(message.notificationType()).isEqualTo("PCB_TASK_CREATED");
        assertThat(message.subject()).isEqualTo("BMS PCB布局布线评审");
        assertThat(message.content()).contains("王鹏飞、刘满红你们好", "附件是BMSPCB文件，请进行硬件评审与EMC评审。",
                "http://10.195.135.149:31792/myTodo");
        assertThat(message.recipients()).extracting(MailMessage.MailRecipient::email)
                .containsExactly("wang@bms.example.com", "liu@bms.example.com");
        assertThat(message.carbonCopies()).extracting(MailMessage.MailRecipient::email)
                .containsExactly("hardware@example.com");
        assertThat(message.attachments()).extracting(MailMessage.MailAttachment::fileName).containsExactly("初版PCB.zip");
    }

    @Test
    void shouldUsePersistedReviewerAssignmentsForTaskCreatedMail() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS001")).thenReturn(user(1L, "BMS001", "王鹏飞", "wang@bms.example.com"));
        when(whitelistMapper.findActiveByEmployeeNo("BMS002")).thenReturn(user(2L, "BMS002", "刘满红", "liu@bms.example.com"));
        when(fileMapper.findById(101L)).thenReturn(file(101L, "初版PCB.zip", "PCB_REVIEW"));
        ReviewTaskRecord task = task(10L, "PCB", "BMS", "PCB_EXPERT:BMS001;EMC_EXPERT:BMS002");
        task.setInitialFileIds("101");
        task.setReviewRoles("PCB_EXPERT,EMC_EXPERT");

        service.enqueueTaskCreated(task);

        MailMessage message = payload();
        assertThat(message.recipients()).extracting(MailMessage.MailRecipient::email)
                .containsExactly("wang@bms.example.com", "liu@bms.example.com");
    }

    @Test
    void shouldIncludeTodoLinkWhenSchematicTaskCreated() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS001")).thenReturn(user(1L, "BMS001", "王鹏飞", "wang@bms.example.com"));
        ReviewTaskRecord task = task(11L, "SCHEMATIC", "VCU", "HARDWARE_EXPERT:BMS001");

        service.enqueueTaskCreated(task);

        MailMessage message = payload();
        assertThat(message.notificationType()).isEqualTo("SCHEMATIC_TASK_CREATED");
        assertThat(message.content()).contains("附件为VCU原理图文件，请进行原理图评审。",
                "http://10.195.135.149:31792/myTodo");
    }

    @Test
    void shouldNotifyHardwareAndEmcExpertsAfterSchematicManagerAssignment() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS001")).thenReturn(user(1L, "BMS001", "硬件专家", "hardware@bms.example.com"));
        when(whitelistMapper.findActiveByEmployeeNo("BMS002")).thenReturn(user(2L, "BMS002", "EMC专家", "emc@bms.example.com"));
        when(fileMapper.findLatestByTaskIdAndCategory(12L, "SCHEMATIC_REVIEW"))
                .thenReturn(List.of(file(120L, "VCU原理图.zip", "SCHEMATIC_REVIEW")));

        service.enqueueSchematicReviewStarted(task(12L, "SCHEMATIC", "VCU",
                "HARDWARE_EXPERT:BMS001;EMC_EXPERT:BMS002;PCB_EXPERT:BMS003"));

        MailMessage message = payload();
        assertThat(message.recipients()).extracting(MailMessage.MailRecipient::email)
                .containsExactly("hardware@bms.example.com", "emc@bms.example.com");
        assertThat(message.attachments()).extracting(MailMessage.MailAttachment::fileName).containsExactly("VCU原理图.zip");
    }

    @Test
    void shouldCarbonCopyHardwareDepartmentMembers() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS001")).thenReturn(user(1L, "BMS001", "王鹏飞", "wang@bms.example.com"));
        when(whitelistMapper.findActiveByEmployeeNo("BMS002")).thenReturn(user(2L, "BMS002", "刘满红", "liu@bms.example.com"));
        service.enqueueTaskCreated(task(10L, "PCB", "BMS", "PCB_EXPERT:BMS001;EMC_EXPERT:BMS002"));

        MailMessage message = payload();
        assertThat(message.carbonCopies()).extracting(MailMessage.MailRecipient::email)
                .containsExactly("hardware@example.com");
    }

    @Test
    void shouldAttachOnlyProcessAndStructureFilesWhenOpeningTheirReviews() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS003")).thenReturn(user(3L, "BMS003", "章俊", "zhang@bms.example.com"));
        when(whitelistMapper.findActiveByEmployeeNo("BMS004")).thenReturn(user(4L, "BMS004", "陈远杰", "chen@bms.example.com"));
        when(fileMapper.findLatestByTaskIdAndCategory(20L, "PCB_PROCESS_REVIEW")).thenReturn(List.of(file(201L, "工艺.zip", "PCB_PROCESS_REVIEW")));
        when(fileMapper.findLatestByTaskIdAndCategory(20L, "PCB_STRUCTURE_REVIEW")).thenReturn(List.of(file(202L, "结构.zip", "PCB_STRUCTURE_REVIEW")));
        ReviewTaskRecord task = task(20L, "PCB", "BMS", "PROCESS_EXPERT:BMS003;STRUCTURE_EXPERT:BMS004");

        service.enqueuePcbStageReview(task, List.of(com.bms.file.domain.FileCategory.PCB_PROCESS_REVIEW,
                com.bms.file.domain.FileCategory.PCB_STRUCTURE_REVIEW));

        MailMessage message = payload();
        assertThat(message.subject()).isEqualTo("BMS工艺评审与结构评审");
        assertThat(message.content()).contains("附件是BMS工艺评审与结构评审文件，请进行工艺评审与结构评审",
                "http://10.195.135.149:31792/myTodo");
        assertThat(message.carbonCopies()).isEmpty();
        assertThat(message.attachments()).extracting(MailMessage.MailAttachment::fileName).containsExactly("工艺.zip", "结构.zip");
    }

    @Test
    void shouldNotifyOnlyStructureExpertsWhenOnlyStructureReviewIsOpened() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS004")).thenReturn(user(4L, "BMS004", "陈远杰", "chen@bms.example.com"));
        when(fileMapper.findLatestByTaskIdAndCategory(20L, "PCB_STRUCTURE_REVIEW"))
                .thenReturn(List.of(file(202L, "结构.zip", "PCB_STRUCTURE_REVIEW")));
        ReviewTaskRecord task = task(20L, "PCB", "BMS", "PROCESS_EXPERT:BMS003;STRUCTURE_EXPERT:BMS004");

        service.enqueuePcbStageReview(task, List.of(com.bms.file.domain.FileCategory.PCB_STRUCTURE_REVIEW));

        MailMessage message = payload();
        assertThat(message.subject()).isEqualTo("BMS结构评审");
        assertThat(message.recipients()).extracting(MailMessage.MailRecipient::email).containsExactly("chen@bms.example.com");
        assertThat(message.carbonCopies()).isEmpty();
        assertThat(message.attachments()).extracting(MailMessage.MailAttachment::fileName).containsExactly("结构.zip");
    }

    @Test
    void shouldNotifyAllSchematicExpertsWithAllLatestFilesWhenFinished() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS001")).thenReturn(user(1L, "BMS001", "王鹏飞", "wang@bms.example.com"));
        when(whitelistMapper.findActiveByEmployeeNo("BMS002")).thenReturn(user(2L, "BMS002", "刘满红", "liu@bms.example.com"));
        for (com.bms.file.domain.FileCategory category : com.bms.file.domain.FileCategory.values()) {
            when(fileMapper.findLatestByTaskIdAndCategory(30L, category.name())).thenReturn(List.of(file(300L + category.ordinal(), category.name() + ".zip", category.name())));
        }
        service.enqueueTaskFinished(task(30L, "SCHEMATIC", "VCU", "HARDWARE_EXPERT:BMS001;EMC_EXPERT:BMS002"));

        MailMessage message = payload();
        assertThat(message.notificationType()).isEqualTo("SCHEMATIC_TASK_FINISHED");
        assertThat(message.subject()).isEqualTo("VCU原理图终版");
        assertThat(message.content()).contains("附件为VCU原理图终版文件。");
        assertThat(message.recipients()).hasSize(2);
        assertThat(message.attachments()).hasSize(1);
    }

    @Test
    void shouldAttachOnlyPcbDesignFilesWhenPcbTaskFinishes() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("BMS003"))
                .thenReturn(user(3L, "BMS003", "PCB 专家", "pcb@bms.example.com"));
        when(fileMapper.findLatestByTaskIdAndCategory(31L, "PCB_REVIEW"))
                .thenReturn(List.of(file(311L, "PCB.zip", "PCB_REVIEW")));
        when(fileMapper.findLatestByTaskIdAndCategory(31L, "PCB_PROCESS_REVIEW"))
                .thenReturn(List.of(file(312L, "工艺.zip", "PCB_PROCESS_REVIEW")));
        when(fileMapper.findLatestByTaskIdAndCategory(31L, "PCB_STRUCTURE_REVIEW"))
                .thenReturn(List.of(file(313L, "结构.zip", "PCB_STRUCTURE_REVIEW")));

        service.enqueueTaskFinished(task(31L, "PCB", "BMS", "PCB_EXPERT:BMS003"));

        MailMessage message = payload();
        assertThat(message.notificationType()).isEqualTo("PCB_TASK_FINISHED");
        assertThat(message.attachments()).extracting(MailMessage.MailAttachment::fileName).containsExactly("PCB.zip");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"created", "optional", "finished", "schematic"})
    void shouldCopyEmcLeadersForEveryTaskMail(String stage) throws Exception {
        ReviewerWhitelistRecord leader = user(1L, "L1", "EMC组长", "leader@example.com");
        leader.setRoles("OTHER, EMC-LEADER");
        ReviewerWhitelistRecord duplicate = user(2L, "L2", "重复邮箱", "LEADER@example.com");
        duplicate.setRoles("EMC-LEADER");
        ReviewerWhitelistRecord other = user(3L, "L3", "其他", "other@example.com");
        other.setRoles("EMC-LEADER-OTHER");
        when(whitelistMapper.findActiveWithSystemRoles()).thenReturn(List.of(leader, duplicate, other));
        ReviewTaskRecord task = task(1L, "PCB", "BMS", "PCB_EXPERT:P1;EMC_EXPERT:E1;PROCESS_EXPERT:T1");
        switch (stage) {
            case "created" -> service.enqueueTaskCreated(task);
            case "optional" -> service.enqueuePcbStageReview(task, List.of(com.bms.file.domain.FileCategory.PCB_PROCESS_REVIEW));
            case "finished" -> service.enqueueTaskFinished(task);
            default -> { task.setReviewType("SCHEMATIC"); service.enqueueSchematicReviewStarted(task); }
        }
        assertThat(payload().carbonCopies()).extracting(MailMessage.MailRecipient::email)
                .containsExactly("hardware@example.com", "leader@example.com");
    }

    @Test
    void shouldUseSingleRecipientGreetingAndResubmissionNotice() throws Exception {
        when(whitelistMapper.findActiveByEmployeeNo("P1")).thenReturn(user(1L, "P1", "张工", "zhang@example.com"));
        service.enqueueTaskResubmitted(task(1L, "PCB", "BMS", "PCB_EXPERT:P1"));
        assertThat(payload().content()).contains("张工您好", "本任务已撤回并重新发起，专家分配已调整");
    }

    @Test
    void shouldNotFallbackToOldMailboxWhenDepartmentIsEmpty() throws Exception {
        when(whitelistMapper.findActiveByDepartmentName("BMS硬件开发部")).thenReturn(List.of());
        service.enqueueTaskCreated(task(1L, "PCB", "BMS", "PCB_EXPERT:P1"));
        assertThat(payload().carbonCopies()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"domain", "created", "resubmitted", "optional", "pcbFinished", "schematicCreated", "schematicStarted", "schematicFinished"})
    void shouldCopyStructureLeaderForEveryTaskMailUsingFullTaskRoles(String entry) throws Exception {
        var leader = user(201L, "L1", "结构领导", "structure@example.com");
        leader.setRoles("OTHER, STRUCTURE-LEADER ");
        var duplicate = user(202L, "L2", "重复", " STRUCTURE@example.com ");
        duplicate.setRoles("STRUCTURE-LEADER");
        var unrelated = user(203L, "L3", "其他", "other@example.com");
        unrelated.setRoles("STRUCTURE-LEADER-OTHER");
        when(whitelistMapper.findActiveWithSystemRoles()).thenReturn(List.of(leader, duplicate, unrelated));
        when(whitelistMapper.findActiveByEmployeeNo("P1")).thenReturn(user(204L, "P1", "专家", "expert@example.com"));
        var task = task(1L, entry.startsWith("schematic") ? "SCHEMATIC" : "PCB", "BMS",
                "PCB_EXPERT:P1;PROCESS_EXPERT:P1;HARDWARE_EXPERT:P1");
        // 结构角色只存在于任务配置，当前邮件收件人分配中没有结构专家。
        task.setReviewRoles("PCB_EXPERT,STRUCTURE_EXPERT");
        switch (entry) {
            case "domain" -> service.enqueueTaskCreated(ReviewTask.draft(1L, ReviewType.PCB, "任务", "BMS", "D1", "设计者", "P1", "BMU", LocalDate.now(),
                    "P1", "专家", List.of(ReviewRole.PCB_EXPERT, ReviewRole.STRUCTURE_EXPERT),
                    List.of(new TaskReviewerAssignment(ReviewRole.PCB_EXPERT, List.of("P1"))), null));
            case "created", "schematicCreated" -> service.enqueueTaskCreated(task);
            case "resubmitted" -> service.enqueueTaskResubmitted(task);
            case "optional" -> service.enqueuePcbStageReview(task, List.of(com.bms.file.domain.FileCategory.PCB_PROCESS_REVIEW));
            case "schematicStarted" -> service.enqueueSchematicReviewStarted(task);
            default -> service.enqueueTaskFinished(task);
        }
        assertThat(payload().carbonCopies()).extracting(MailMessage.MailRecipient::email).containsExactlyElementsOf(
                entry.equals("optional") ? List.of("structure@example.com") : List.of("hardware@example.com", "structure@example.com"));
    }

    @Test
    void shouldRemoveAllActualRecipientsFromDepartmentAndLeaderCopiesIgnoringEmailCaseAndSpaces() throws Exception {
        var hardware = user(1L, "A", "硬件成员", "HARDWARE@example.com");
        var emc = user(2L, "B", "EMC领导", " emc@example.com ");
        emc.setRoles("EMC-LEADER");
        var structure = user(3L, "C", "结构领导", " STRUCTURE@example.com ");
        structure.setRoles("STRUCTURE-LEADER");
        var remaining = user(4L, "D", "其他领导", "remaining@example.com");
        remaining.setRoles("EMC-LEADER,STRUCTURE-LEADER");
        when(whitelistMapper.findActiveByEmployeeNo("A")).thenReturn(hardware);
        when(whitelistMapper.findActiveByEmployeeNo("B")).thenReturn(emc);
        when(whitelistMapper.findActiveByEmployeeNo("C")).thenReturn(structure);
        when(whitelistMapper.findActiveWithSystemRoles()).thenReturn(List.of(emc, structure, remaining));
        var task = task(1L, "PCB", "BMS", "PCB_EXPERT:A;EMC_EXPERT:B,C;STRUCTURE_EXPERT:D");
        service.enqueueTaskCreated(task);

        var message = payload();
        assertThat(message.recipients()).extracting(MailMessage.MailRecipient::email)
                .containsExactly("HARDWARE@example.com", "emc@example.com", "STRUCTURE@example.com");
        assertThat(message.carbonCopies()).extracting(MailMessage.MailRecipient::email).containsExactly("remaining@example.com");
    }

    private MailMessage payload() throws Exception {
        ArgumentCaptor<OutboxEventRecord> captor = ArgumentCaptor.forClass(OutboxEventRecord.class);
        verify(outboxEventMapper).insert(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(ReviewMailNotificationApplicationService.REVIEW_MAIL_EVENT);
        return objectMapper.readValue(captor.getValue().payload(), MailMessage.class);
    }

    private ReviewerWhitelistRecord user(long id, String employeeNo, String name, String email) {
        ReviewerWhitelistRecord record = new ReviewerWhitelistRecord();
        record.setId(id); record.setEmployeeNo(employeeNo); record.setDisplayName(name); record.setEmail(email);
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
        task.setReviewRoles(reviewerAssignments.substring(0, reviewerAssignments.indexOf(':'))); task.setExpertLeaderEmployeeNo("BMS009");
        return task;
    }
}
