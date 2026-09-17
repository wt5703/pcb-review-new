package com.leapmotor.pcbreview.task.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
