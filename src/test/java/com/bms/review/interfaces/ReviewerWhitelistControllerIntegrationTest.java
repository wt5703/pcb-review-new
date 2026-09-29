package com.bms.review.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-29
 * @description 验证白名单列表按单一关键词匹配工号或姓名，并返回稳定的分页响应。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewerWhitelistControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldPageAndSearchReviewerWhitelistByEmployeeNoOrName() throws Exception {
        mockMvc.perform(post("/reviewer-whitelists/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"BMS009\",\"pageNo\":1,\"pageSize\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNo").value(1))
                .andExpect(jsonPath("$.data.items[0].employeeNo").value("BMS009"));

        mockMvc.perform(post("/reviewer-whitelists/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"张腾瑜\",\"pageNo\":1,\"pageSize\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].displayName").value("张腾瑜"));
    }
}
