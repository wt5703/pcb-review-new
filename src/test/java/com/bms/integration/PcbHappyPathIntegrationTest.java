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
        long taskId = createAndSubmitTask();

        transition(taskId, "START_PCB_EXPERT_REVIEW", 10L, "DESIGNER");
        submitNoOpinion(taskId, 10L, "DESIGNER");

        transition(taskId, "PREPARE_PCB_MUTUAL_ASSIGNMENT", 1L, "PCB_LEADER");
        assignAndTransition(taskId, "START_PCB_MUTUAL_REVIEW", "PCB_MUTUAL_CHECK", 21L, 1L, "PCB_LEADER");
        submitNoOpinion(taskId, 21L, "HARDWARE_EXPERT");

        transition(taskId, "REQUEST_FINISH", 1L, "PCB_LEADER");
        transition(taskId, "FINISH", 1L, "PCB_LEADER");

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
                        .content("{\"action\":\"FINISH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_CONFLICT"));
    }

    private long createAndSubmitTask() throws Exception {
        String taskRequest = """
                {"reviewType":"PCB","taskName":"PCB 全链路验收","projectName":"BMS","designerId":10,"designName":"BMS-P1","pcbType":"BMU","initialFiles":[{"fileId":"company-pcb-p1","fileName":"BMS-P1.pcb","fileSize":8}]}
                """;
        MvcResult result = mockMvc.perform(post("/tasks/submit").contentType(MediaType.APPLICATION_JSON).content(taskRequest)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PCB_PENDING_REVIEW"))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private void assignAndTransition(long taskId, String action, String reviewRole, long reviewerId, long operatorId, String operatorRole) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", String.valueOf(operatorId))
                        .header("X-Mock-Roles", operatorRole)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"assignedRole\":\"" + reviewRole + "\",\"reviewerIds\":[" + reviewerId + "]}"))
                .andExpect(status().isOk());
    }

    private void submitNoOpinion(long taskId, long userId, String role) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/workflow/reviewers/me/submit-no-opinion", taskId)
                        .header("X-Mock-User-Id", String.valueOf(userId))
                        .header("X-Mock-Roles", role))
                .andExpect(status().isOk());
    }

    private void transition(long taskId, String action, long userId, String role) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", String.valueOf(userId))
                        .header("X-Mock-Roles", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\"}"))
                .andExpect(status().isOk());
    }
}
