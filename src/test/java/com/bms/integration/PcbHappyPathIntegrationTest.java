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
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 以真实 REST 接口验证 PCB 任务从创建、设计文件登记、专家与互检多人处理，到申请结束、归档和不可重新打开的核心成功路径。
 */
@SpringBootTest
@com.bms.TestDatabase
@org.springframework.boot.test.mock.mockito.MockBean(com.bms.notification.application.ReviewMailNotificationApplicationService.class)
@AutoConfigureMockMvc
class PcbHappyPathIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewFileMapper reviewFileMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void shouldCompletePcbTaskFromCreationToImmutableArchive() throws Exception {
        long taskId = createAndSubmitTask();
        submitNoOpinion(taskId, "PCB_REVIEW", "BMS003");
        registerLatestPcbFile(taskId);

        transition(taskId, "START_PCB_MATUAL_ASSIGNMENT", "BMS010", "DESIGNER");
        assignAndTransition(taskId, "START_PCB_MATUAL_REVIEW", "BMS006", "BMS001", "PCB_LEADER");
        submitNoOpinion(taskId, "MUTUAL_CHECK_ITEM", "BMS006");

        // 互检结束后由设计者重新上传终版，才能进入待结束。
        mockMvc.perform(multipart("/files/upload")
                        .file(new MockMultipartFile("file", "BMS-final.pcb", "application/octet-stream", new byte[]{1, 2, 3}))
                        .param("taskId", Long.toString(taskId))
                        .param("fileCategory", "PCB_REVIEW")
                        .header("userId", "BMS010").header("userName", "测试用户"))
                .andExpect(status().isOk());
        transition(taskId, "PENDING_FINISH", "BMS010", "DESIGNER");
        jdbcTemplate.update("UPDATE reviewer_whitelist SET roles=? WHERE employee_no=?", "PCB-LEADER", "BMS001");
        jdbcTemplate.update("UPDATE reviewer_whitelist SET roles=? WHERE employee_no=?", "HARDWARE-MANAGER", "BMS002");
        transition(taskId, "FINISH", "BMS001", "PCB_LEADER");
        transition(taskId, "FINISH", "BMS002", "HARDWARE_MANAGER");

        mockMvc.perform(get("/tasks/{taskId}/archive", taskId)
                        .header("userId", "BMS010")
                        .header("userName", "测试用户"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowNodes").isArray())
                .andExpect(jsonPath("$.data.flowNodes.length()").value(6));

        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", "BMS001")
                        .header("userName", "测试用户")
                        .param("actions", "FINISH"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_CONFLICT"));
    }

    private long createAndSubmitTask() throws Exception {
        String fileId = "c778c14e-6f1a-4f4f-9f11-100000000004";
        ReviewFileRecord pending = new ReviewFileRecord();
        pending.setId(reviewFileMapper.nextId()); pending.setFileId(fileId); pending.setTaskId(null); pending.setFileCategory("PCB_REVIEW");
        pending.setFileName("BMS-P1.pcb"); pending.setFileFormat("pcb"); pending.setLatest(true);
        pending.setFileSize(8L); pending.setMd5("happy-path-md5"); pending.setResourcePath("company-pcb-p1"); pending.setUploadedByEmployeeNo("BMS010");
        reviewFileMapper.insert(pending);
        String taskRequest = """
                {"reviewType":"PCB","taskName":"PCB 全链路验收","projectName":"BMS","designerId":10,"designName":"BMS-P1","pcbType":"BMU","reviewRoles":["PCB_EXPERT"],"reviewerAssignments":[{"reviewRole":"PCB_EXPERT","reviewerEmployeeNos":["BMS003"]}],"files":["c778c14e-6f1a-4f4f-9f11-100000000004"]}
                """;
        MvcResult result = mockMvc.perform(post("/tasks/submit").contentType(MediaType.APPLICATION_JSON).content(taskRequest)
                        .header("userId", "BMS010")
                        .header("userName", "测试用户"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PCB_EXPERT_REVIEWING"))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private void assignAndTransition(long taskId, String action, String reviewerEmployeeNo, String operatorEmployeeNo, String operatorRole) throws Exception {
        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", operatorEmployeeNo)
                        .header("userName", "测试用户")
                        .param("actions", action).param("reviewerEmployeeNos", reviewerEmployeeNo))
                .andExpect(status().isOk());
    }

    private void transition(long taskId, String action, String employeeNo, String role) throws Exception {
        mockMvc.perform(multipart("/tasks/{taskId}/workflow/transitions", taskId)
                        .header("userId", employeeNo)
                        .header("userName", "测试用户")
                        .param("actions", action))
                .andExpect(status().isOk());
    }

    private void submitNoOpinion(long taskId, String sourceType, String employeeNo) throws Exception {
        mockMvc.perform(post("/tasks/{taskId}/opinions/no-opinion", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"" + sourceType + "\"}")
                        .header("userId", employeeNo)
                        .header("userName", "测试用户"))
                .andExpect(status().isOk());
    }

    private void registerLatestPcbFile(long taskId) {
        registerLatestFile(taskId, "PCB_REVIEW", "BMS-P1-最新版本.pcb", "pcb-file-latest");
        registerLatestFile(taskId, "PCB_PROCESS_REVIEW", "BMS-P1-工艺图.zip", "process-file-latest");
        registerLatestFile(taskId, "PCB_STRUCTURE_REVIEW", "BMS-P1-结构图.zip", "structure-file-latest");
    }

    private void registerLatestFile(long taskId, String fileCategory, String fileName, String fileId) {
        ReviewFileRecord file = new ReviewFileRecord();
        file.setId(reviewFileMapper.nextId()); file.setTaskId(taskId); file.setFileCategory(fileCategory);
        file.setFileName(fileName); file.setFileFormat(fileName.substring(fileName.lastIndexOf('.') + 1));
        file.setFileSize(12L); file.setMd5("latest-pcb-md5"); file.setFileId(fileId); file.setResourcePath("company-" + fileId);
        file.setLatest(true); file.setUploadedByEmployeeNo("BMS010"); file.setUploadedStage("PCB_EXPERT_REVIEWING");
        reviewFileMapper.insert(file);
    }
}
