package com.bms.task.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import com.bms.file.infrastructure.ReviewFileMapper;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    void shouldCreateAndSubmitTaskWithMultipleFilesInSingleMultipartRequest() throws Exception {
        MockMultipartFile taskPart = new MockMultipartFile("task", "task.json", MediaType.APPLICATION_JSON_VALUE, """
                {"reviewType":"PCB","taskName":"多文件一体化创建","projectName":"BMS","designerId":10,"designerName":"设计者A","designName":"BMS-P2","pcbType":"BMU","expectedCompletedDate":"2026-09-30","expertLeaderId":1,"expertLeaderName":"王鹏飞","reviewRoles":["PCB_EXPERT"]}
                """.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile fileOne = new MockMultipartFile("files", "BMS-P2.pcb", "application/octet-stream", "pcb-data".getBytes());
        MockMultipartFile fileTwo = new MockMultipartFile("files", "BMS-P2.sch", "application/octet-stream", "schematic-data".getBytes());

        String response = mockMvc.perform(multipart("/tasks").file(taskPart).file(fileOne).file(fileTwo)
                        .param("submit", "true")
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PCB_PENDING_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        long taskId = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.id")).longValue();
        org.assertj.core.api.Assertions.assertThat(reviewFileMapper.findLatestByTaskId(taskId)).hasSize(2);
    }

    @Test
    void shouldCreateSubmitAndQueryTaskThroughRestApi() throws Exception {
        String createRequest = """
                {"reviewType":"PCB","taskName":"REST-BMS PCB评审","projectName":"BMS","designerId":10,"designName":"BMS-P1","pcbType":"BMU"}
                """;

        String createResponse = mockMvc.perform(post("/tasks")
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long taskId = ((Number) com.jayway.jsonpath.JsonPath.read(createResponse, "$.data.id")).longValue();

        mockMvc.perform(post("/tasks/{taskId}/submit", taskId)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialFileIds\":[3001]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PCB_PENDING_REVIEW"));

        mockMvc.perform(get("/tasks")
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .param("taskName", "REST-BMS")
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(taskId));
    }

    @Test
    void shouldRejectCreateTaskForRoleWithoutCreatePermission() throws Exception {
        mockMvc.perform(post("/tasks")
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewType\":\"PCB\",\"taskName\":\"无权任务\",\"projectName\":\"BMS\",\"designerId\":20,\"designName\":\"BMS-P1\",\"pcbType\":\"BMU\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
