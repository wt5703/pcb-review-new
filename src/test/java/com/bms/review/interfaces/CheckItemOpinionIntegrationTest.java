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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 验证互检固定检查项不合格时通过整单提交创建同源意见，防止检查项问题与意见处理脱节。
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

    @Test
    void shouldCompleteFailedCheckItemWithOpinionInOneBatchSubmission() throws Exception {
        long taskId = 8501L;
        taskMapper.insert(task(taskId));
        reviewerMapper.insert(reviewer(taskId));

        mockMvc.perform(post("/check-item-templates")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewType\":\"PCB\",\"categoryName\":\"集成测试类别\",\"items\":[{\"itemName\":\"线距检查\"}]}"))
                .andExpect(status().isOk());

        String itemsResponse = mockMvc.perform(get("/tasks/{taskId}/check-items", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category.itemName").value("集成测试类别"))
                .andExpect(jsonPath("$.data[0].items[0].opinion").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        long itemId = ((java.util.List<Number>) JsonPath.read(itemsResponse, "$.data[0].items[*].id")).get(0).longValue();

        mockMvc.perform(put("/tasks/{taskId}/check-items/batch", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":" + itemId + ",\"result\":\"FAIL\",\"comment\":\"线距不足\",\"richText\":\"线距不满足要求\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].result").value("FAIL"))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].opinion.id").isNumber());

        mockMvc.perform(get("/tasks/{taskId}/check-items", taskId)
                        .header("X-Mock-User-Id", "20")
                        .header("X-Mock-Roles", "HARDWARE_EXPERT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].items[0].opinion.result").value("FAIL"))
                .andExpect(jsonPath("$.data[0].items[0].opinion.comment").value("线距不足"))
                .andExpect(jsonPath("$.data[0].items[0].opinion.richText").value("线距不满足要求"))
                .andExpect(jsonPath("$.data[0].items[0].opinion.content").doesNotExist())
                .andExpect(jsonPath("$.data[0].items[0].opinion.status").doesNotExist());
    }

    @Test
    void shouldListTemplateCategoriesAndLogicallyDisableCategoryWithChildren() throws Exception {
        String created = mockMvc.perform(post("/check-item-templates")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewType\":\"PCB\",\"categoryName\":\"模板树类别\",\"items\":[{\"itemName\":\"模板树检查项\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category.itemName").value("模板树类别"))
                .andExpect(jsonPath("$.data.items[0].itemName").value("模板树检查项"))
                .andReturn().getResponse().getContentAsString();
        long categoryId = ((Number) JsonPath.read(created, "$.data.category.id")).longValue();
        long itemId = ((Number) JsonPath.read(created, "$.data.items[0].id")).longValue();

        mockMvc.perform(get("/check-item-templates")
                        .param("reviewType", "PCB")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.category.id == " + categoryId + ")].items[0].itemName").value("模板树检查项"));

        mockMvc.perform(put("/check-item-templates/items/{id}", itemId)
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemName\":\"已修改检查项\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(itemId))
                .andExpect(jsonPath("$.data.itemName").value("已修改检查项"));

        mockMvc.perform(delete("/check-item-templates/{id}", itemId)
                        .param("category", "CATEGORY")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/check-item-templates/{id}", itemId)
                        .param("category", "ITEM")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/check-item-templates")
                        .param("reviewType", "PCB")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.category.id == " + categoryId + ")].items[0].enabled").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.category.id == " + categoryId + ")].items[0].reviewType").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.category.id == " + categoryId + ")].items[0].opinion").doesNotExist());

        mockMvc.perform(delete("/check-item-templates/{id}", categoryId)
                        .param("category", "CATEGORY")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/check-item-templates")
                        .param("reviewType", "PCB")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.category.id == " + categoryId + ")].category.enabled").value(false));
    }

    @Test
    void shouldCreateCategoryWithoutItems() throws Exception {
        mockMvc.perform(post("/check-item-templates")
                        .header("X-Mock-User-Id", "1")
                        .header("X-Mock-Roles", "PCB_LEADER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewType\":\"PCB\",\"categoryName\":\"暂未配置子项的大类\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category.itemName").value("暂未配置子项的大类"))
                .andExpect(jsonPath("$.data.items").isEmpty());
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

}
