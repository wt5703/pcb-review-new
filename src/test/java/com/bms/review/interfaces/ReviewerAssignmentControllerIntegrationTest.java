package com.bms.review.interfaces;

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
 * @description 通过统一流程 REST 验证 PCB 互检多人分配和当前节点可分配人员查询授权。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewerAssignmentControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewTaskMapper taskMapper;

    @Test
    void shouldAssignMultipleReviewersThroughUnifiedWorkflowTransition() throws Exception {
        long taskId = 8401L;
        taskMapper.insert(task(taskId));

        mockMvc.perform(get("/tasks/{taskId}/workflow/assignable-reviewers", taskId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reviewRole").value("PCB_MUTUAL_CHECK"));

        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"START_PCB_MATUAL_REVIEW\"],\"assignedRole\":\"PCB_MUTUAL_CHECK\",\"reviewerIds\":[20,21]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("MUTUAL_CHECK_REVIEWING"))
                .andExpect(jsonPath("$.data.assignedReviewers.length()").value(2))
                .andExpect(jsonPath("$.data.assignedReviewers[0].status").value("PENDING"));

    }

    @Test
    void shouldRejectRoleWithoutCurrentNodeAssignmentPermission() throws Exception {
        long taskId = 8402L;
        taskMapper.insert(task(taskId));

        mockMvc.perform(get("/tasks/{taskId}/workflow/assignable-reviewers", taskId)
                        .header("X-Mock-User-Id", "88")
                        .header("X-Mock-Roles", "PROCESS_EXPERT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private ReviewTaskRecord task(long taskId) {
        return task(taskId, TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT);
    }

    private ReviewTaskRecord task(long taskId, TaskStatus status) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(taskId);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("人员分配接口测试");
        task.setProjectName("BMS");
        task.setDesignerId(10L);
        task.setDesignName("BMS-P1");
        task.setPcbType("BMU");
        task.setStatus(status.name());
        task.setInitialFileIds("8403");
        task.setVersion(0L);
        return task;
    }
}
