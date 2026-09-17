package com.leapmotor.pcbreview.review.interfaces;

import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
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
 * @description 通过 REST 验证 PCB 互检多人分配、个人无意见提交和受限角色的人员关系查询授权，覆盖任务 4 的关键处理路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewerAssignmentControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewTaskMapper taskMapper;

    @Test
    void shouldAssignMultipleReviewersAndAllowAssignedReviewerToSubmitNoOpinion() throws Exception {
        long taskId = 8401L;
        taskMapper.insert(task(taskId));

        mockMvc.perform(post("/tasks/{taskId}/reviewers", taskId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"PCB_MUTUAL_CHECK\",\"reviewerIds\":[20,21]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

        mockMvc.perform(post("/tasks/{taskId}/reviewers/me/no-opinion", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tasks/{taskId}/reviewers", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .param("role", "PCB_MUTUAL_CHECK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUBMITTED"));
    }

    @Test
    void shouldRejectUnassignedRestrictedExpertFromReviewersList() throws Exception {
        long taskId = 8402L;
        taskMapper.insert(task(taskId));

        mockMvc.perform(get("/tasks/{taskId}/reviewers", taskId)
                        .header("X-Mock-User-Id", "88")
                        .header("X-Mock-Roles", "PROCESS_EXPERT")
                        .param("role", "PCB_MUTUAL_CHECK"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private ReviewTaskRecord task(long taskId) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(taskId);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("人员分配接口测试");
        task.setProjectName("BMS");
        task.setDesignerId(10L);
        task.setDesignName("BMS-P1");
        task.setPcbType("BMU");
        task.setStatus(TaskStatus.MUTUAL_REVIEWING.name());
        task.setInitialFileIds("8403");
        task.setVersion(0L);
        return task;
    }
}
