package com.bms.review.interfaces;

import com.jayway.jsonpath.JsonPath;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 验证互检固定检查项不合格时先创建同源意见、再关联意见完成检查项的端到端闭环，防止检查项问题与意见处理脱节。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CheckItemOpinionIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReviewTaskMapper taskMapper;
    @Autowired
    private TaskReviewerMapper reviewerMapper;
    @Autowired
    private ReviewFileMapper fileMapper;

    @Test
    void shouldCompleteFailedCheckItemOnlyWithOpinionFromSameTaskAndItem() throws Exception {
        long taskId = 8501L;
        taskMapper.insert(task(taskId));
        reviewerMapper.insert(reviewer(taskId));
        fileMapper.insert(file(taskId));

        mockMvc.perform(post("/check-item-templates")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewType\":\"PCB\",\"itemKey\":\"integration-failed-item\",\"itemName\":\"线距检查\",\"sortNo\":1}"))
                .andExpect(status().isOk());

        String itemsResponse = mockMvc.perform(get("/tasks/{taskId}/check-items", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long itemId = ((Number) JsonPath.read(itemsResponse, "$.data[0].id")).longValue();

        String opinionResponse = mockMvc.perform(post("/tasks/{taskId}/opinions", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"MUTUAL_CHECK_ITEM\",\"sourceItemId\":" + itemId + ",\"content\":\"线距不满足要求\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long opinionId = ((Number) JsonPath.read(opinionResponse, "$.data.id")).longValue();

        mockMvc.perform(put("/tasks/{taskId}/check-items/{itemId}", taskId, itemId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"FAIL\",\"comment\":\"线距不足\",\"linkedOpinionId\":" + opinionId + ",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("FAIL"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(post("/tasks/{taskId}/check-items/{itemId}/attachments", taskId, itemId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":8503,\"sortNo\":1}"))
                .andExpect(status().isOk());
    }

    private ReviewTaskRecord task(long taskId) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(taskId);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("检查项意见联动测试");
        task.setProjectName("BMS");
        task.setDesignerId(10L);
        task.setDesignName("BMS-P1");
        task.setPcbType("BMU");
        task.setStatus(TaskStatus.MUTUAL_REVIEWING.name());
        task.setInitialFileIds("8503");
        task.setVersion(0L);
        return task;
    }

    private TaskReviewerRecord reviewer(long taskId) {
        TaskReviewerRecord reviewer = new TaskReviewerRecord();
        reviewer.setId(8502L);
        reviewer.setTaskId(taskId);
        reviewer.setReviewRole("PCB_MUTUAL_CHECK");
        reviewer.setReviewerId(20L);
        reviewer.setProcessStatus("PENDING");
        reviewer.setAssignedBy(1L);
        reviewer.setNoOpinion(false);
        reviewer.setVersion(0L);
        return reviewer;
    }

    private ReviewFileRecord file(long taskId) {
        ReviewFileRecord file = new ReviewFileRecord();
        file.setId(8503L);
        file.setTaskId(taskId);
        file.setFileCategory("PCB_SCHEMATIC");
        file.setBusinessFileKey("check-image");
        file.setFileName("check.png");
        file.setFileSize(12L);
        file.setMd5("check-attachment-md5");
        file.setVersionNo(1);
        file.setCompanyFileId("mock-check-file");
        file.setLatest(true);
        file.setUploadedBy(20L);
        return file;
    }
}
