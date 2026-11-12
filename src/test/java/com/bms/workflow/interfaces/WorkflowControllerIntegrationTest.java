package com.bms.workflow.interfaces;

import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void shouldArchiveOnlyAfterBothApproversConfirmAndRejectFurtherTransitions() throws Exception {
        long taskId = 8301L;
        ReviewTaskRecord task = task(taskId);
        task.setStatus(TaskStatus.PENDING_FINISH.name());
        taskMapper.insert(task);
        fileMapper.insert(file(taskId));

        jdbcTemplate.update("UPDATE reviewer_whitelist SET roles=? WHERE employee_no=?", "PCB-LEADER", "BMS001");
        jdbcTemplate.update("UPDATE reviewer_whitelist SET roles=? WHERE employee_no=?", "HARDWARE-MANAGER", "BMS002");

        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS003").param("actions", "FINISH"))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS001").param("actions", "FINISH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("PENDING_FINISH"))
                .andExpect(jsonPath("$.data.assignedReviewerEmployeeNos[0]").value("BMS002"));
        mockMvc.perform(get("/tasks/my").header("userId", "BMS001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/tasks/my").header("userId", "BMS002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].actions[0]").value("FINISH_TASK"));
        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS001").param("actions", "FINISH"))
                .andExpect(status().isConflict());

        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS002")
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
        jdbcTemplate.update("INSERT INTO review_opinion (id, task_id, source_type, severity, is_no_opinion, opinion_comment, raised_by_employee_no, raised_by_name, opinion_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
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

    @Test
    void designerCanWithdrawInitialPcbTaskAndResubmitWithTheSameId() throws Exception {
        long taskId = 8301L;
        ReviewTaskRecord task = task(taskId);
        task.setStatus(TaskStatus.PCB_EXPERT_REVIEWING.name());
        task.setAssignedReviewerEmployeeNos("BMS003");
        taskMapper.insert(task);
        fileMapper.insert(file(taskId));

        mockMvc.perform(workflowRequest(taskId, "withdraw", "BMS003", """
                        {"reason":"更新设计"}
                        """))
                .andExpect(status().isForbidden());
        mockMvc.perform(workflowRequest(taskId, "withdraw", "BMS010", """
                        {"reason":"  "}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(workflowRequest(taskId, "withdraw", "BMS010", """
                        {"reason":"更新设计"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("PENDING_INITIATION"))
                .andExpect(jsonPath("$.data.assignedReviewerEmployeeNos").isEmpty());
        assertFlow(taskId, "WITHDRAW", "BMS010", "更新设计");

        mockMvc.perform(post("/tasks/submit").param("taskId", String.valueOf(taskId))
                        .header("userId", "BMS010").header("userName", "设计者")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reviewType":"PCB","taskName":"重新发起","projectName":"BMS","designName":"BMS-P1",
                                 "pcbType":"BMU","reviewRoles":["PCB_EXPERT"],
                                 "reviewerAssignments":[{"reviewRole":"PCB_EXPERT","reviewerEmployeeNos":["BMS003"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(taskId))
                .andExpect(jsonPath("$.data.status").value("PCB_EXPERT_REVIEWING"));
        org.assertj.core.api.Assertions.assertThat(fileMapper.findLatestByTaskId(taskId)).hasSize(1);
    }

    @Test
    void schematicWithdrawalRequiresInitialStageAndNoReviewOpinion() throws Exception {
        long taskId = 8301L;
        ReviewTaskRecord task = task(taskId);
        task.setReviewType(ReviewType.SCHEMATIC.name());
        taskMapper.insert(task);
        String reason = """
                {"reason":"补充设计"}
                """;

        mockMvc.perform(workflowRequest(taskId, "withdraw", "BMS010", reason))
                .andExpect(status().isConflict());

        jdbcTemplate.update("UPDATE review_task SET task_status=? WHERE id=?",
                TaskStatus.MUTUAL_CHECK_PENDING_ASSIGNMENT.name(), taskId);
        insertOpinion(taskId);
        mockMvc.perform(workflowRequest(taskId, "withdraw", "BMS010", reason))
                .andExpect(status().isConflict());
        jdbcTemplate.update("DELETE FROM review_opinion WHERE task_id=?", taskId);

        mockMvc.perform(workflowRequest(taskId, "withdraw", "BMS010", reason))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("PENDING_INITIATION"));
    }

    @Test
    void onlyDesignerCanTerminateAndTaskCannotAdvanceAfterwards() throws Exception {
        long taskId = 8301L;
        ReviewTaskRecord task = task(taskId);
        task.setAssignedReviewerEmployeeNos("BMS003");
        taskMapper.insert(task);
        String reason = """
                {"reason":"项目取消"}
                """;

        mockMvc.perform(workflowRequest(taskId, "terminate", "BMS003", reason))
                .andExpect(status().isForbidden());
        mockMvc.perform(workflowRequest(taskId, "terminate", "BMS010", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(workflowRequest(taskId, "terminate", "BMS010", reason))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("TERMINATED"))
                .andExpect(jsonPath("$.data.assignedReviewerEmployeeNos").isEmpty());
        assertFlow(taskId, "TERMINATE", "BMS010", "项目取消");

        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS010").param("actions", "PENDING_FINISH"))
                .andExpect(status().isConflict());
        mockMvc.perform(workflowRequest(taskId, "terminate", "BMS010", reason))
                .andExpect(status().isConflict());
    }

    @Test
    void assignedExpertCanReturnToPreviousNodeWithoutRemovingFilesOrOpinions() throws Exception {
        long taskId = 8301L;
        ReviewTaskRecord task = task(taskId);
        task.setStatus(TaskStatus.PENDING_FINISH.name());
        task.setReviewerAssignments("PCB_EXPERT:BMS003");
        task.setMutualCheckReviewerEmployeeNos("BMS006");
        taskMapper.insert(task);
        fileMapper.insert(file(taskId));
        insertOpinion(taskId);
        String request = """
                {"targetStatus":"MUTUAL_CHECK_REVIEWING","reason":"重新核对检查项"}
                """;

        mockMvc.perform(get("/tasks/{taskId}/workflow/return-targets", taskId).header("userId", "BMS009"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/tasks/{taskId}/workflow/return-targets", taskId).header("userId", "BMS003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.contains("PENDING_INITIATION",
                        "PCB_EXPERT_REVIEWING", "PCB_PROCESS_STRUCTURE_REVIEWING",
                        "MUTUAL_CHECK_PENDING_ASSIGNMENT", "MUTUAL_CHECK_REVIEWING")));

        mockMvc.perform(workflowRequest(taskId, "return", "BMS009", request))
                .andExpect(status().isForbidden());
        mockMvc.perform(workflowRequest(taskId, "return", "BMS003", """
                        {"targetStatus":"SCHEMATIC_REVIEWING","reason":"重新核对检查项"}
                        """))
                .andExpect(status().isConflict());
        mockMvc.perform(workflowRequest(taskId, "return", "BMS003", request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("MUTUAL_CHECK_REVIEWING"))
                .andExpect(jsonPath("$.data.assignedReviewerEmployeeNos[0]").value("BMS006"));

        mockMvc.perform(get("/tasks/{taskId}/flow-records", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fromStatus").value("PENDING_FINISH"))
                .andExpect(jsonPath("$.data[0].toStatus").value("MUTUAL_CHECK_REVIEWING"))
                .andExpect(jsonPath("$.data[0].nextOperators[0].employeeNo").value("BMS006"))
                .andExpect(jsonPath("$.data[0].stageDurationSeconds").doesNotExist())
                .andExpect(jsonPath("$.data[0].comment").value("重新核对检查项"));
        assertFlow(taskId, "RETURN", "BMS003", "重新核对检查项");
        org.assertj.core.api.Assertions.assertThat(fileMapper.findLatestByTaskId(taskId)).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_opinion WHERE task_id=?", Integer.class, taskId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HARDWARE-MANAGER", "SCHEMATIC-LEADER", "PCB-LEADER"})
    void managerOrLeaderCanReturnAcrossMultipleNodes(String role) throws Exception {
        long taskId = 8301L;
        ReviewTaskRecord task = task(taskId);
        task.setStatus(TaskStatus.PENDING_FINISH.name());
        task.setReviewerAssignments("PCB_EXPERT:BMS003");
        taskMapper.insert(task);
        jdbcTemplate.update("UPDATE reviewer_whitelist SET roles=? WHERE employee_no=?", role, "BMS001");

        mockMvc.perform(workflowRequest(taskId, "return", "BMS001", """
                        {"targetStatus":"PCB_EXPERT_REVIEWING","reason":"重新做首轮评审"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromStatus").value("PENDING_FINISH"))
                .andExpect(jsonPath("$.data.toStatus").value("PCB_EXPERT_REVIEWING"))
                .andExpect(jsonPath("$.data.assignedReviewerEmployeeNos[0]").value("BMS003"));

        assertFlow(taskId, "RETURN", "BMS001", "重新做首轮评审");
    }

    @Test
    void schematicFinishRequiresTheDesignatedLeaderAndHardwareManager() throws Exception {
        long taskId = 8301L;
        ReviewTaskRecord task = task(taskId);
        task.setReviewType(ReviewType.SCHEMATIC.name());
        task.setStatus(TaskStatus.PENDING_FINISH.name());
        taskMapper.insert(task);
        jdbcTemplate.update("UPDATE reviewer_whitelist SET roles=? WHERE employee_no=?", "SCHEMATIC-LEADER", "BMS001");
        jdbcTemplate.update("UPDATE reviewer_whitelist SET roles=? WHERE employee_no=?", "SCHEMATIC-LEADER", "BMS002");
        jdbcTemplate.update("UPDATE reviewer_whitelist SET roles=? WHERE employee_no=?", "HARDWARE-MANAGER", "BMS004");

        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS002").param("actions", "FINISH"))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS004").param("actions", "FINISH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedReviewerEmployeeNos").value(org.hamcrest.Matchers.contains("BMS001")));
        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS001").param("actions", "FINISH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("FINISHED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder workflowRequest(
            long taskId, String action, String employeeNo, String json) {
        return post("/tasks/{taskId}/workflow/{action}", taskId, action)
                .header("userId", employeeNo).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private void assertFlow(long taskId, String action, String employeeNo, String reason) {
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForList(
                "SELECT flow_comment FROM task_flow_record WHERE task_id=? AND action_code=? AND operate_employee_no=?",
                String.class, taskId, action, employeeNo)).containsExactly(reason);
    }

    private void insertOpinion(long taskId) {
        jdbcTemplate.update("INSERT INTO review_opinion (id, task_id, source_type, severity, is_no_opinion, "
                        + "opinion_comment, raised_by_employee_no, raised_by_name, opinion_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                8306L, taskId, "PCB_REVIEW", "GENERAL", false, "检查意见", "BMS003", "专家", "PENDING_REPLY");
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
