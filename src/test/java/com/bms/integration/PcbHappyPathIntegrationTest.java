package com.bms.integration;

import com.jayway.jsonpath.JsonPath;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
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
    @Autowired
    private ReviewFileMapper reviewFileMapper;

    @Test
    void shouldCompletePcbTaskFromCreationToImmutableArchive() throws Exception {
        long taskId = createAndSubmitTask();
        registerLatestPcbFile(taskId);

        transition(taskId, "START_PCB_MATUAL_ASSIGNMENT", 10L, "DESIGNER");
        assignAndTransition(taskId, "START_PCB_MATUAL_REVIEW", "PCB_MUTUAL_CHECK", 21L, 1L, "PCB_LEADER");

        transition(taskId, "PREPARE_FINISH", 10L, "DESIGNER");
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
                        .content("{\"actions\":[\"FINISH\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_CONFLICT"));
    }

    private long createAndSubmitTask() throws Exception {
        String fileId = "c778c14e-6f1a-4f4f-9f11-100000000004";
        ReviewFileRecord pending = new ReviewFileRecord();
        pending.setId(reviewFileMapper.nextId()); pending.setFileId(fileId); pending.setTaskId(null); pending.setFileCategory("TASK_CREATION");
        pending.setBusinessFileKey(null); pending.setFileName("BMS-P1.pcb"); pending.setFileFormat("pcb"); pending.setLatest(true);
        pending.setFileSize(8L); pending.setMd5("happy-path-md5"); pending.setResourcePath("company-pcb-p1"); pending.setUploadedBy(10L);
        reviewFileMapper.insert(pending);
        String taskRequest = """
                {"reviewType":"PCB","taskName":"PCB 全链路验收","projectName":"BMS","designerId":10,"designName":"BMS-P1","pcbType":"BMU","files":["c778c14e-6f1a-4f4f-9f11-100000000004"]}
                """;
        MvcResult result = mockMvc.perform(post("/tasks/submit").contentType(MediaType.APPLICATION_JSON).content(taskRequest)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PCB_EXPERT_REVIEWING"))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private void assignAndTransition(long taskId, String action, String reviewRole, long reviewerId, long operatorId, String operatorRole) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", String.valueOf(operatorId))
                        .header("X-Mock-Roles", operatorRole)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"" + action + "\"],\"assignedRole\":\"" + reviewRole + "\",\"reviewerIds\":[" + reviewerId + "]}"))
                .andExpect(status().isOk());
    }

    private void transition(long taskId, String action, long userId, String role) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", String.valueOf(userId))
                        .header("X-Mock-Roles", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"" + action + "\"]}"))
                .andExpect(status().isOk());
    }

    private void registerLatestPcbFile(long taskId) {
        ReviewFileRecord file = new ReviewFileRecord();
        file.setId(reviewFileMapper.nextId()); file.setTaskId(taskId); file.setFileCategory("PCB_REVIEW");
        file.setBusinessFileKey("PCB_REVIEW"); file.setFileName("BMS-P1-最新版本.pcb"); file.setFileFormat("pcb");
        file.setFileSize(12L); file.setMd5("latest-pcb-md5"); file.setFileId("pcb-file-latest"); file.setResourcePath("company-pcb-latest");
        file.setLatest(true); file.setUploadedBy(10L); file.setUploadedStage("PCB_EXPERT_REVIEWING");
        reviewFileMapper.insert(file);
    }
}
