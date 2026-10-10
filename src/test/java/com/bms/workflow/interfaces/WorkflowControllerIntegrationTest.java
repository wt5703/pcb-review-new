package com.bms.workflow.interfaces;

import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
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
    private ReviewFileMapper fileMapper;

    @Test
    void shouldFinishArchiveAndRejectSecondTransitionThroughRestApi() throws Exception {
        long taskId = 8301L;
        taskMapper.insert(task(taskId));
        fileMapper.insert(file(taskId));

        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-Employee-No", "BMS001")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"FINISH\"],\"comment\":\"完成\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("FINISHED"));

        mockMvc.perform(get("/tasks/{taskId}/archive", taskId)
                        .header("X-Mock-Employee-No", "BMS001")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.finalStatus").value("FINISHED"));

        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-Employee-No", "BMS001")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"FINISH\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_CONFLICT"));
    }

    @Test
    void shouldStartProcessAndStructureReviewAfterStageFilesWereUploaded() throws Exception {
        long taskId = 8303L;
        ReviewTaskRecord task = task(taskId);
        task.setStatus(TaskStatus.PCB_EXPERT_REVIEWING.name());
        task.setReviewRoles("PROCESS_EXPERT");
        taskMapper.insert(task);
        fileMapper.insert(file(taskId, 8304L, "PCB_PROCESS_REVIEW"));
        fileMapper.insert(file(taskId, 8305L, "PCB_STRUCTURE_REVIEW"));

        mockMvc.perform(post("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("X-Mock-Employee-No", "BMS010")
                        .header("X-Mock-Roles", "DESIGNER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actions":["START_PCB_STRUCTURE_REVIEW","START_PCB_PROCESS_REVIEW"],"comment":"文件已上传，开启工艺和结构评审"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("PCB_PROCESS_STRUCTURE_REVIEWING"));
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
        task.setStatus(TaskStatus.MUTUAL_CHECK_REVIEWING.name());
        task.setInitialFileIds("8302");
        task.setVersion(0L);
        return task;
    }

    private ReviewFileRecord file(long taskId) {
        return file(taskId, 8302L, "PCB_REVIEW");
    }

    private ReviewFileRecord file(long taskId, long fileId, String category) {
        ReviewFileRecord file = new ReviewFileRecord();
        file.setId(fileId);
        file.setTaskId(taskId);
        file.setFileCategory(category);
        file.setFileName("BMS.pcb");
        file.setFileSize(100L);
        file.setMd5("workflow-md5");
        file.setFileId("mock-file-8302");
        file.setResourcePath("mock-8302");
        file.setLatest(true);
        file.setUploadedBy(10L);
        return file;
    }
}
