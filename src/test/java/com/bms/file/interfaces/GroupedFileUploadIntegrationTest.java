package com.bms.file.interfaces;

import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@com.bms.TestDatabase
@AutoConfigureMockMvc
class GroupedFileUploadIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ReviewTaskMapper tasks;
    @Autowired ReviewFileMapper files;
    @Autowired com.bms.notification.infrastructure.OutboxEventMapper outbox;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @BeforeEach
    void task() {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(9901L); task.setReviewType("PCB"); task.setTaskName("分类上传测试"); task.setProjectName("BMS");
        task.setDesignName("测试PCB"); task.setPcbType("BMU");
        task.setDesignerEmployeeNo("BMS010"); task.setStatus("PCB_EXPERT_REVIEWING"); task.setInitialFileIds(""); task.setVersion(0L);
        tasks.insert(task);
    }

    @Test
    void uploadsMultipleTypesAndExposesLatestFilesToUnassignedUsers() throws Exception {
        mvc.perform(multipart("/files/upload/grouped")
                        .file(file("PCB_REVIEW", "a.pcb")).file(file("PCB_REVIEW", "b.pdf"))
                        .file(file("PCB_STRUCTURE_REVIEW", "c.zip")).header("userId", "BMS010")
                        .param("taskId", "9901").param("fileCategories", "PCB_REVIEW", "PCB_STRUCTURE_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fileCategory").value("PCB_REVIEW"))
                .andExpect(jsonPath("$.data[0].files.length()").value(2))
                .andExpect(jsonPath("$.data[1].fileCategory").value("PCB_STRUCTURE_REVIEW"))
                .andExpect(jsonPath("$.data[1].files.length()").value(1));
        mvc.perform(get("/tasks/9901/files/latest").header("userId", "UNASSIGNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.PCB_REVIEW[*].fileName").value(org.hamcrest.Matchers.containsInAnyOrder("a.pcb", "b.pdf")))
                .andExpect(jsonPath("$.data.PCB_REVIEW[*].fileCategory").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PCB_REVIEW"))))
                .andExpect(jsonPath("$.data.PCB_STRUCTURE_REVIEW.length()").value(1))
                .andExpect(jsonPath("$.data.PCB_STRUCTURE_REVIEW[0].fileName").value("c.zip"))
                .andExpect(jsonPath("$.data.PCB_PROCESS_REVIEW").isEmpty())
                .andExpect(jsonPath("$.data.SCHEMATIC_REVIEW").isEmpty());
        long id = files.findLatestByTaskId(9901L).get(0).getId();
        mvc.perform(post("/files/download").param("fileId", Long.toString(id)).header("userId", "UNASSIGNED"))
                .andExpect(status().isOk());
        mvc.perform(multipart("/files/upload/grouped").file(file("PCB_REVIEW", "new.pcb")).header("userId", "BMS010")
                        .param("taskId", "9901").param("fileCategories", "PCB_REVIEW"))
                .andExpect(status().isOk());
        assertThat(files.findLatestByTaskId(9901L)).extracting(record -> record.getFileName()).containsExactlyInAnyOrder("new.pcb", "c.zip");
        mvc.perform(get("/tasks/9901/files/latest").header("userId", "UNASSIGNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.PCB_REVIEW.length()").value(1))
                .andExpect(jsonPath("$.data.PCB_REVIEW[0].fileName").value("new.pcb"))
                .andExpect(jsonPath("$.data.PCB_STRUCTURE_REVIEW.length()").value(1))
                .andExpect(jsonPath("$.data.PCB_STRUCTURE_REVIEW[0].fileName").value("c.zip"));
    }

    @Test
    void returnsAllCategoryKeysWithEmptyArraysBeforeAnyFilesAreUploaded() throws Exception {
        mvc.perform(get("/tasks/9901/files/latest").header("userId", "UNASSIGNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.PCB_REVIEW").isArray())
                .andExpect(jsonPath("$.data.PCB_REVIEW").isEmpty())
                .andExpect(jsonPath("$.data.PCB_PROCESS_REVIEW").isArray())
                .andExpect(jsonPath("$.data.PCB_PROCESS_REVIEW").isEmpty())
                .andExpect(jsonPath("$.data.PCB_STRUCTURE_REVIEW").isArray())
                .andExpect(jsonPath("$.data.PCB_STRUCTURE_REVIEW").isEmpty())
                .andExpect(jsonPath("$.data.SCHEMATIC_REVIEW").isArray())
                .andExpect(jsonPath("$.data.SCHEMATIC_REVIEW").isEmpty());
    }

    @Test
    void rejectsIncompleteGroupsAndNonDesignerUploads() throws Exception {
        mvc.perform(multipart("/files/upload/grouped").file(file("PCB_REVIEW", "a.pcb")).header("userId", "BMS010")
                        .param("taskId", "9901").param("fileCategories", "PCB_REVIEW", "PCB_STRUCTURE_REVIEW"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/files/upload/grouped").file(file("PCB_REVIEW", "a.pcb")).header("userId", "UNASSIGNED")
                        .param("taskId", "9901").param("fileCategories", "PCB_REVIEW"))
                .andExpect(status().isForbidden());
        assertThat(files.findByTaskId(9901L)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.bms.file.domain.FileCategory.class,
            names = {"PCB_PROCESS_REVIEW", "PCB_STRUCTURE_REVIEW"})
    void sendsOptionalReviewMailOnlyWhenRequestedAfterGroupedUpload(com.bms.file.domain.FileCategory category) throws Exception {
        var task = tasks.findById(9901L);
        task.setStatus("PCB_PROCESS_STRUCTURE_REVIEWING");
        task.setReviewerAssignments("PROCESS_EXPERT:BMS003;STRUCTURE_EXPERT:BMS004");
        tasks.update(task);
        mvc.perform(multipart("/files/upload/grouped").file(file(category.name(), "old.zip"))
                        .header("userId", "BMS010").param("taskId", "9901").param("fileCategories", category.name()))
                .andExpect(status().isOk());
        assertThat(outbox.findByAggregate("REVIEW_TASK", 9901L)).isEmpty();

        mvc.perform(multipart("/files/upload/grouped").file(file(category.name(), "a.zip")).file(file(category.name(), "b.pdf"))
                        .header("userId", "BMS010").param("taskId", "9901").param("fileCategories", category.name()).param("sendMail", "true"))
                .andExpect(status().isOk());
        var events = outbox.findByAggregate("REVIEW_TASK", 9901L);
        assertThat(events).hasSize(1);
        var mail = json.readValue(events.get(0).getPayload(), com.bms.notification.domain.MailMessage.class);
        assertThat(mail.notificationType()).isEqualTo("PCB_OPTIONAL_REVIEW");
        assertThat(mail.subject()).contains(category == com.bms.file.domain.FileCategory.PCB_PROCESS_REVIEW ? "工艺评审" : "结构评审");
        assertThat(mail.attachments()).extracting(com.bms.notification.domain.MailMessage.MailAttachment::fileName)
                .containsExactlyInAnyOrder("a.zip", "b.pdf");
        assertThat(mail.attachments()).extracting(com.bms.notification.domain.MailMessage.MailAttachment::fileCategory).containsOnly(category.name());

        mvc.perform(multipart("/files/upload/grouped").file(file(category.name(), "silent.zip"))
                        .header("userId", "BMS010").param("taskId", "9901").param("fileCategories", category.name()).param("sendMail", "false"))
                .andExpect(status().isOk());
        assertThat(outbox.findByAggregate("REVIEW_TASK", 9901L)).hasSize(1);
    }

    @Test
    void doesNotSendOptionalMailBeforeStageStartsOrWhenAnyGroupIsInvalid() throws Exception {
        mvc.perform(multipart("/files/upload/grouped").file(file("PCB_PROCESS_REVIEW", "a.zip"))
                        .header("userId", "BMS010").param("taskId", "9901").param("fileCategories", "PCB_PROCESS_REVIEW").param("sendMail", "true"))
                .andExpect(status().isConflict());
        var task = tasks.findById(9901L);
        task.setStatus("PCB_PROCESS_STRUCTURE_REVIEWING");
        tasks.update(task);
        mvc.perform(multipart("/files/upload/grouped").file(file("PCB_PROCESS_REVIEW", "a.zip"))
                        .file(new MockMultipartFile("PCB_STRUCTURE_REVIEW", "empty.zip", "application/octet-stream", new byte[0]))
                        .header("userId", "BMS010").param("taskId", "9901").param("fileCategories", "PCB_PROCESS_REVIEW", "PCB_STRUCTURE_REVIEW").param("sendMail", "true"))
                .andExpect(status().isBadRequest());
        assertThat(files.findByTaskId(9901L)).isEmpty();
        assertThat(outbox.findByAggregate("REVIEW_TASK", 9901L)).isEmpty();
    }

    @Test
    void combinesProcessAndStructureGroupsInOneMailAfterAllFilesAreStored() throws Exception {
        var task = tasks.findById(9901L);
        task.setStatus("PCB_PROCESS_STRUCTURE_REVIEWING");
        task.setReviewerAssignments("PROCESS_EXPERT:BMS003;STRUCTURE_EXPERT:BMS004");
        tasks.update(task);
        mvc.perform(multipart("/files/upload/grouped")
                        .file(file("PCB_PROCESS_REVIEW", "process.zip")).file(file("PCB_PROCESS_REVIEW", "process.pdf"))
                        .file(file("PCB_STRUCTURE_REVIEW", "structure.zip")).file(file("PCB_REVIEW", "layout.pcb"))
                        .header("userId", "BMS010").param("taskId", "9901")
                        .param("fileCategories", "PCB_PROCESS_REVIEW", "PCB_STRUCTURE_REVIEW", "PCB_REVIEW").param("sendMail", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
        assertThat(files.findLatestByTaskId(9901L)).hasSize(4);
        var events = outbox.findByAggregate("REVIEW_TASK", 9901L);
        assertThat(events).hasSize(1);
        var mail = json.readValue(events.get(0).getPayload(), com.bms.notification.domain.MailMessage.class);
        assertThat(mail.subject()).contains("工艺评审与结构评审");
        assertThat(mail.recipients()).hasSize(2);
        assertThat(mail.attachments()).extracting(com.bms.notification.domain.MailMessage.MailAttachment::fileName)
                .containsExactlyInAnyOrder("process.zip", "process.pdf", "structure.zip");
    }

    @Test
    void batchUploadKeepsItsOriginalBehaviorAndDoesNotSendMail() throws Exception {
        mvc.perform(multipart("/files/upload/batch").file(file("files", "a.zip")).file(file("files", "b.pdf"))
                        .header("userId", "BMS010").param("taskId", "9901").param("fileCategory", "PCB_PROCESS_REVIEW")
                        .param("sendMail", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        assertThat(outbox.findByAggregate("REVIEW_TASK", 9901L)).isEmpty();
    }

    private MockMultipartFile file(String category, String name) {
        return new MockMultipartFile(category, name, "application/octet-stream", new byte[]{1, 2, 3});
    }
}
