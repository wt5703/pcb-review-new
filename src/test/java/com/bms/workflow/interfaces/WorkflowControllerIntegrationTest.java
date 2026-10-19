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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 通过真实 REST、Mock 身份、测试数据库和流程服务验证任务结束、归档读取及结束后再次流转被拒绝的全链路行为。
 */
@SpringBootTest
@com.bms.TestDatabase
@org.springframework.boot.test.mock.mockito.MockBean(com.bms.notification.application.ReviewMailNotificationApplicationService.class)
@AutoConfigureMockMvc
class WorkflowControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewTaskMapper taskMapper;
    @Autowired
    private ReviewFileMapper fileMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldFinishArchiveAndRejectSecondTransitionThroughRestApi() throws Exception {
        long taskId = 8301L;
        ReviewTaskRecord task = task(taskId);
        task.setStatus(TaskStatus.PENDING_FINISH.name());
        taskMapper.insert(task);
        fileMapper.insert(file(taskId));

        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS001")
                        .header("userName", "测试用户")
                        .param("actions", "FINISH").param("comment", "完成"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("FINISHED"));

        mockMvc.perform(get("/tasks/{taskId}/archive", taskId)
                        .header("userId", "BMS001")
                        .header("userName", "测试用户"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowNodes").isArray());

        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS001")
                        .header("userName", "测试用户")
                        .param("actions", "FINISH"))
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
        jdbcTemplate.update("INSERT INTO review_opinion (id, task_id, source_type, severity, is_no_opinion, comment, raised_by_employee_no, raised_by_name, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                8306L, taskId, "PCB_REVIEW", "GENERAL", true, "无意见", "BMS001", "组长", "CONFIRMED_PASS");
        fileMapper.insert(file(taskId, 8304L, "PCB_PROCESS_REVIEW"));
        fileMapper.insert(file(taskId, 8305L, "PCB_STRUCTURE_REVIEW"));

        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS010")
                        .header("userName", "测试用户")
                        .param("actions", "START_PCB_STRUCTURE_REVIEW", "START_PCB_PROCESS_REVIEW")
                        .param("comment", "文件已上传，开启工艺和结构评审"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("PCB_PROCESS_STRUCTURE_REVIEWING"));
    }

    private ReviewTaskRecord task(long taskId) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(taskId);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("流程集成测试任务");
        task.setProjectName("BMS");
        task.setDesignerEmployeeNo("BMS010");
        task.setExpertLeaderEmployeeNo("BMS001");
        task.setExpertLeaderName("组长");
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
        file.setFileFormat("pcb");
        file.setFileSize(100L);
        file.setMd5("workflow-md5");
        file.setFileId("mock-file-" + fileId);
        file.setResourcePath("mock-" + fileId);
        file.setLatest(true);
        file.setUploadedByEmployeeNo("BMS010");
        return file;
    }
}
