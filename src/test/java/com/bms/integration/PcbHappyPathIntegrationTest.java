package com.bms.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 以真实 REST 接口验证 PCB 任务从创建、设计文件登记、专家与互检多人处理，到申请结束、归档和不可重新打开的核心成功路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PcbHappyPathIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCompletePcbTaskFromCreationToImmutableArchive() throws Exception {
        long taskId = createTask();
        long fileId = registerPcbFile(taskId);
        submitTask(taskId, fileId);

        assign(taskId, "PCB_EXPERT", 20L, 10L, "DESIGNER");
        transition(taskId, "START_PCB_EXPERT_REVIEW", 1L, 10L, "DESIGNER");
        submitNoOpinion(taskId, 20L, "HARDWARE_EXPERT");

        transition(taskId, "PREPARE_PCB_MUTUAL_ASSIGNMENT", 2L, 1L, "PCB_LEADER");
        assign(taskId, "PCB_MUTUAL_CHECK", 21L, 1L, "PCB_LEADER");
        transition(taskId, "START_PCB_MUTUAL_REVIEW", 3L, 1L, "PCB_LEADER");
        submitNoOpinion(taskId, 21L, "HARDWARE_EXPERT");

        transition(taskId, "REQUEST_FINISH", 4L, 1L, "PCB_LEADER");
        transition(taskId, "FINISH", 5L, 1L, "PCB_LEADER");

        mockMvc.perform(get("/tasks/{taskId}/archive", taskId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.finalStatus").value("FINISHED"));

        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"FINISH\",\"version\":6}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_CONFLICT"));
    }

    private long createTask() throws Exception {
        MvcResult result = mockMvc.perform(post("/tasks")
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewType\":\"PCB\",\"taskName\":\"PCB 全链路验收\",\"projectName\":\"BMS\","
                                + "\"designerId\":10,\"designName\":\"BMS-P1\",\"pcbType\":\"BMU\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private void submitTask(long taskId, long fileId) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/submit", taskId)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialFileIds\":[" + fileId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PCB_PENDING_REVIEW"));
    }

    private long registerPcbFile(long taskId) throws Exception {
        MvcResult sessionResult = mockMvc.perform(post("/tasks/{taskId}/files/upload-sessions", taskId)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"PCB_SCHEMATIC\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String sessionId = JsonPath.read(sessionResult.getResponse().getContentAsString(), "$.data.uploadSessionId");

        MvcResult fileResult = mockMvc.perform(post("/tasks/{taskId}/files", taskId)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadSessionId\":\"" + sessionId + "\",\"category\":\"PCB_SCHEMATIC\","
                                + "\"businessFileKey\":\"BMS-P1\",\"fileName\":\"BMS.pcb\",\"fileSize\":100,\"md5\":\"e2e-md5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andReturn();
        return ((Number) JsonPath.read(fileResult.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private void assign(long taskId, String reviewRole, long reviewerId, long operatorId, String operatorRole) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/reviewers", taskId)
                        .header("X-Mock-User-Id", String.valueOf(operatorId))
                        .header("X-Mock-Roles", operatorRole)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + reviewRole + "\",\"reviewerIds\":[" + reviewerId + "]}"))
                .andExpect(status().isOk());
    }

    private void submitNoOpinion(long taskId, long userId, String role) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/reviewers/me/no-opinion", taskId)
                        .header("X-Mock-User-Id", String.valueOf(userId))
                        .header("X-Mock-Roles", role))
                .andExpect(status().isOk());
    }

    private void transition(long taskId, String action, long version, long userId, String role) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", String.valueOf(userId))
                        .header("X-Mock-Roles", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }
}
