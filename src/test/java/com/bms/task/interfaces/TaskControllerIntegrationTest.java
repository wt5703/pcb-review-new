package com.bms.task.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 通过 HTTP 请求验证评审任务创建、提交和分页查询接口能够正确贯通 Mock 身份、应用服务、数据库迁移与统一响应结构。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewFileMapper reviewFileMapper;

    @Test
    void shouldCreateAndSubmitTaskWithCompanyFileReferences() throws Exception {
        String pcbFileId = "c778c14e-6f1a-4f4f-9f11-100000000001";
        String schematicFileId = "c778c14e-6f1a-4f4f-9f11-100000000002";
        reviewFileMapper.insert(pending(pcbFileId, "BMS-P2.pcb", 8L, "company-p2-pcb"));
        reviewFileMapper.insert(pending(schematicFileId, "BMS-P2.sch", 13L, "company-p2-sch"));
        String request = """
                {"reviewType":"PCB","taskName":"多文件一体化创建","projectName":"BMS","designName":"BMS-P2","pcbType":"BMU","expectedCompletedDate":"2026-09-30","reviewRoles":["PCB_EXPERT"],"files":["c778c14e-6f1a-4f4f-9f11-100000000001","c778c14e-6f1a-4f4f-9f11-100000000002"]}
                """;

        String response = mockMvc.perform(post("/tasks/submit").contentType(MediaType.APPLICATION_JSON).content(request)
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PCB_EXPERT_REVIEWING"))
                .andReturn().getResponse().getContentAsString();
        long taskId = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.id")).longValue();
        org.assertj.core.api.Assertions.assertThat(reviewFileMapper.findLatestByTaskId(taskId))
                .hasSize(2)
                .extracting(item -> item.getResourcePath())
                .containsExactlyInAnyOrder("company-p2-pcb", "company-p2-sch");
    }

    @Test
    void shouldSaveDraftWithMultipleCompanyFileReferences() throws Exception {
        String firstFileId = "c778c14e-6f1a-4f4f-9f11-100000000021";
        String secondFileId = "c778c14e-6f1a-4f4f-9f11-100000000022";
        reviewFileMapper.insert(pending(firstFileId, "BMS-DRAFT-A.pcb", 8L, "company-draft-a"));
        reviewFileMapper.insert(pending(secondFileId, "BMS-DRAFT-B.sch", 13L, "company-draft-b"));
        String request = """
                {"reviewType":"PCB","taskName":"多文件草稿保存","projectName":"BMS","designName":"BMS-DRAFT","pcbType":"BMU","expectedCompletedDate":"2026-09-30","reviewRoles":["PCB_EXPERT"],"files":["c778c14e-6f1a-4f4f-9f11-100000000021","c778c14e-6f1a-4f4f-9f11-100000000022"]}
                """;

        String response = mockMvc.perform(post("/tasks/save").contentType(MediaType.APPLICATION_JSON).content(request)
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long taskId = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.id")).longValue();

        org.assertj.core.api.Assertions.assertThat(reviewFileMapper.findLatestByTaskId(taskId))
                .hasSize(2)
                .extracting(ReviewFileRecord::getFileId)
                .containsExactlyInAnyOrder(firstFileId, secondFileId);

        mockMvc.perform(post("/tasks/save").contentType(MediaType.APPLICATION_JSON).content(request)
                        .param("taskId", String.valueOf(taskId))
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(taskId));

        org.assertj.core.api.Assertions.assertThat(reviewFileMapper.findLatestByTaskId(taskId))
                .hasSize(2)
                .extracting(ReviewFileRecord::getFileId)
                .containsExactlyInAnyOrder(firstFileId, secondFileId);
    }

    @Test
    void shouldCreateSubmitAndQueryTaskThroughRestApi() throws Exception {
        String taskRequest = """
                {"reviewType":"PCB","taskName":"REST-BMS PCB评审","projectName":"BMS","designName":"BMS-P1","pcbType":"BMU"}
                """;
        String createResponse = mockMvc.perform(post("/tasks/save").contentType(MediaType.APPLICATION_JSON).content(taskRequest)
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long taskId = ((Number) com.jayway.jsonpath.JsonPath.read(createResponse, "$.data.id")).longValue();

        String updateTaskRequest = """
                {"reviewType":"PCB","taskName":"REST-BMS PCB评审（已编辑）","projectName":"BMS","designName":"BMS-P1","pcbType":"BMU"}
                """;
        mockMvc.perform(post("/tasks/save").contentType(MediaType.APPLICATION_JSON).content(updateTaskRequest)
                        .param("taskId", String.valueOf(taskId))
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(taskId))
                .andExpect(jsonPath("$.data.taskName").value("REST-BMS PCB评审（已编辑）"));

        String submitTaskRequest = """
                {"reviewType":"PCB","taskName":"REST-BMS PCB评审（已编辑）","projectName":"BMS","designName":"BMS-P1","pcbType":"BMU","files":["c778c14e-6f1a-4f4f-9f11-100000000003"]}
                """;
        reviewFileMapper.insert(pending("c778c14e-6f1a-4f4f-9f11-100000000003", "BMS-P1.pcb", 8L, "company-p1"));
        mockMvc.perform(post("/tasks/submit").contentType(MediaType.APPLICATION_JSON).content(submitTaskRequest)
                        .param("taskId", String.valueOf(taskId))
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PCB_EXPERT_REVIEWING"));

        mockMvc.perform(get("/tasks").param("keyword", "REST-BMS").param("pageNo", "1").param("pageSize", "10")
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(taskId));

        mockMvc.perform(get("/tasks/{taskId}", taskId)
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(taskId))
                .andExpect(jsonPath("$.data.taskName").value("REST-BMS PCB评审（已编辑）"));
    }

    @Test
    void shouldUseDefaultUserCenterMockInsteadOfFrontendRoleHeader() throws Exception {
        String taskRequest = """
                {"reviewType":"PCB","taskName":"无权任务","projectName":"BMS","designName":"BMS-P1","pcbType":"BMU"}
                """;
        mockMvc.perform(post("/tasks/save").contentType(MediaType.APPLICATION_JSON).content(taskRequest)
                        .header("X-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designerName").value("王鹏飞"));
    }

    private ReviewFileRecord pending(String fileId, String fileName, long fileSize, String resourcePath) {
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(reviewFileMapper.nextId()); record.setFileId(fileId); record.setTaskId(null); record.setFileCategory("PCB_REVIEW");
        record.setFileName(fileName); record.setFileFormat("pcb"); record.setLatest(true);
        record.setFileSize(fileSize); record.setMd5("test-md5-" + fileId); record.setResourcePath(resourcePath); record.setUploadedBy(10L);
        return record;
    }
}
