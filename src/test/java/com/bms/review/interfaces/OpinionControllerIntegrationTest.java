package com.bms.review.interfaces;

import com.jayway.jsonpath.JsonPath;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 通过 REST 验证专家提出意见、设计者答复、原提出人确认通过及统计看板更新的完整意见闭环。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpinionControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewTaskMapper taskMapper;

    @Test
    void shouldReplyAndConfirmOpinionWithoutRestartingTaskReview() throws Exception {
        long taskId = 8601L;
        taskMapper.insert(task(taskId));

        String raiseResponse = mockMvc.perform(post("/tasks/{taskId}/opinions", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"EXPERT_REVIEW\",\"comment\":\"<p>请调整走线</p><img src='data:image/png;base64,AA==' alt='问题截图' />\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REPLY"))
                .andExpect(jsonPath("$.data.comment").value("<p>请调整走线</p><img src='data:image/png;base64,AA==' alt='问题截图' />"))
                .andReturn().getResponse().getContentAsString();
        long opinionId = ((Number) JsonPath.read(raiseResponse, "$.data.id")).longValue();

        mockMvc.perform(post("/opinions/{opinionId}/reply", opinionId)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replyType\":\"ACCEPT\",\"reason\":\"已修正\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"));

        mockMvc.perform(post("/opinions/{opinionId}/confirm", opinionId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passed\":true,\"comment\":\"确认通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED_PASS"));

        mockMvc.perform(get("/tasks/{taskId}/opinions", taskId)
                        .param("pageNo", "1")
                        .param("pageSize", "10")
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNo").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(opinionId));

        mockMvc.perform(get("/tasks/{taskId}/opinions/summary", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.confirmedPass").value(1));
    }

    @Test
    void shouldRecordNoOpinionAsPassedAuditWithoutAddingDesignerWork() throws Exception {
        long taskId = 8602L;
        ReviewTaskRecord task = task(taskId);
        task.setStatus(TaskStatus.PCB_EXPERT_REVIEWING.name());
        taskMapper.insert(task);

        mockMvc.perform(post("/tasks/{taskId}/opinions/no-opinion", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"EXPERT_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.severity").value("PASS"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED_PASS"));

        mockMvc.perform(get("/tasks/{taskId}/opinions/summary", taskId)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.unsubmittedReviewers.length()").value(0));
    }

    @Test
    void shouldAllowRaiserToEditAndDeleteOpinionBeforeDesignerReplies() throws Exception {
        long taskId = 8603L;
        taskMapper.insert(task(taskId));
        String response = mockMvc.perform(post("/tasks/{taskId}/opinions", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"EXPERT_REVIEW\",\"severity\":\"GENERAL\",\"comment\":\"原始意见\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long opinionId = ((Number) JsonPath.read(response, "$.data.id")).longValue();

        mockMvc.perform(put("/opinions/{opinionId}", opinionId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"SERIOUS\",\"comment\":\"<p>修改后的意见</p>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.severity").value("SERIOUS"))
                .andExpect(jsonPath("$.data.comment").value("<p>修改后的意见</p>"));

        mockMvc.perform(delete("/opinions/{opinionId}", opinionId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tasks/{taskId}/opinions", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    private ReviewTaskRecord task(long taskId) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(taskId);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("意见闭环接口测试");
        task.setProjectName("BMS");
        task.setDesignerId(10L);
        task.setDesignName("BMS-P1");
        task.setPcbType("BMU");
        task.setStatus(TaskStatus.MUTUAL_CHECK_REVIEWING.name());
        task.setInitialFileIds("8602");
        task.setVersion(0L);
        return task;
    }

}
