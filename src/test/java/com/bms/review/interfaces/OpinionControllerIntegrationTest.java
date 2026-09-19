package com.bms.review.interfaces;

import com.jayway.jsonpath.JsonPath;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 通过 REST 验证专家提出意见、设计者携带新版本答复、原提出人确认通过及统计看板更新的完整意见闭环。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpinionControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewTaskMapper taskMapper;
    @Autowired
    private TaskReviewerMapper reviewerMapper;

    @Test
    void shouldReplyAndConfirmOpinionWithoutRestartingTaskReview() throws Exception {
        long taskId = 8601L;
        taskMapper.insert(task(taskId));
        reviewerMapper.insert(reviewer(taskId));

        String raiseResponse = mockMvc.perform(post("/tasks/{taskId}/opinions", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"EXPERT_REVIEW\",\"content\":\"请调整走线\",\"fileVersionId\":101}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REPLY"))
                .andReturn().getResponse().getContentAsString();
        long opinionId = ((Number) JsonPath.read(raiseResponse, "$.data.id")).longValue();

        mockMvc.perform(post("/opinions/{opinionId}/replies", opinionId)
                        .header("X-Mock-User-Id", "10")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replyType\":\"ACCEPT\",\"reason\":\"已在新版修正\",\"fileVersionId\":102}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"));

        mockMvc.perform(post("/opinions/{opinionId}/confirmations", opinionId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passed\":true,\"comment\":\"确认通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED_PASS"));

        mockMvc.perform(get("/tasks/{taskId}/opinions/summary", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.confirmedPass").value(1));
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
        task.setStatus(TaskStatus.MUTUAL_REVIEWING.name());
        task.setInitialFileIds("8602");
        task.setVersion(0L);
        return task;
    }

    private TaskReviewerRecord reviewer(long taskId) {
        TaskReviewerRecord reviewer = new TaskReviewerRecord();
        reviewer.setId(8602L);
        reviewer.setTaskId(taskId);
        reviewer.setReviewRole("PCB_EXPERT");
        reviewer.setReviewerId(20L);
        reviewer.setProcessStatus("PENDING");
        reviewer.setAssignedBy(1L);
        reviewer.setNoOpinion(false);
        reviewer.setVersion(0L);
        return reviewer;
    }
}
