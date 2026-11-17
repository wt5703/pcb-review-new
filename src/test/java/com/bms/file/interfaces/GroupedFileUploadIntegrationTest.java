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
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
        long id = files.findLatestByTaskId(9901L).get(0).getId();
        mvc.perform(post("/files/download").param("fileId", Long.toString(id)).header("userId", "UNASSIGNED"))
                .andExpect(status().isOk());
        mvc.perform(multipart("/files/upload/grouped").file(file("PCB_REVIEW", "new.pcb")).header("userId", "BMS010")
                        .param("taskId", "9901").param("fileCategories", "PCB_REVIEW"))
                .andExpect(status().isOk());
        assertThat(files.findLatestByTaskId(9901L)).extracting(record -> record.getFileName()).containsExactlyInAnyOrder("new.pcb", "c.zip");
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

    private MockMultipartFile file(String category, String name) {
        return new MockMultipartFile(category, name, "application/octet-stream", new byte[]{1, 2, 3});
    }
}
