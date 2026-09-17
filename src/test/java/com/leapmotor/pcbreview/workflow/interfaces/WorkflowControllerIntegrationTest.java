package com.leapmotor.pcbreview.workflow.interfaces;

import com.leapmotor.pcbreview.file.infrastructure.ReviewFileMapper;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileRecord;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerRecord;
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
 * @date 2026-09-18
 * @description 通过真实 REST、Mock 身份、Flyway 数据库和流程服务验证任务结束、归档读取及结束后再次流转被拒绝的全链路行为。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewTaskMapper taskMapper;
    @Autowired
    private TaskReviewerMapper reviewerMapper;
    @Autowired
    private ReviewFileMapper fileMapper;

    @Test
    void shouldFinishArchiveAndRejectSecondTransitionThroughRestApi() throws Exception {
        long taskId = 8301L;
        taskMapper.insert(task(taskId));
        reviewerMapper.insert(reviewer(taskId));
        fileMapper.insert(file(taskId));

        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"FINISH\",\"version\":0,\"comment\":\"完成\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("FINISHED"));

        mockMvc.perform(get("/tasks/{taskId}/archive", taskId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.finalStatus").value("FINISHED"));

        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"FINISH\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_CONFLICT"));
    }

    private ReviewTaskRecord task(long taskId) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(taskId);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("流程集成测试任务");
        task.setProjectName("BMS");
        task.setDesignerId(10L);
        task.setDesignName("BMS-P1");
        task.setPcbType("BMU");
        task.setStatus(TaskStatus.PENDING_FINISH_CONFIRMATION.name());
        task.setInitialFileIds("8302");
        task.setVersion(0L);
        return task;
    }

    private TaskReviewerRecord reviewer(long taskId) {
        TaskReviewerRecord reviewer = new TaskReviewerRecord();
        reviewer.setId(8302L);
        reviewer.setTaskId(taskId);
        reviewer.setReviewRole("PCB_MUTUAL_CHECK");
        reviewer.setReviewerId(20L);
        reviewer.setProcessStatus("SUBMITTED");
        reviewer.setAssignedBy(1L);
        reviewer.setNoOpinion(true);
        reviewer.setVersion(0L);
        return reviewer;
    }

    private ReviewFileRecord file(long taskId) {
        ReviewFileRecord file = new ReviewFileRecord();
        file.setId(8302L);
        file.setTaskId(taskId);
        file.setFileCategory("PCB_SCHEMATIC");
        file.setBusinessFileKey("pcb-design");
        file.setFileName("BMS.pcb");
        file.setFileSize(100L);
        file.setMd5("workflow-md5");
        file.setVersionNo(1);
        file.setCompanyFileId("mock-8302");
        file.setLatest(true);
        file.setUploadedBy(10L);
        return file;
    }
}
