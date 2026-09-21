package com.bms.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 验证运行时可以公开生成 OpenAPI JSON 契约，确保后端接口变更可供联调和后续客户端生成工具使用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldExposeOpenApiContract() throws Exception {
        mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("PCB评审平台后端 API"))
                .andExpect(jsonPath("$.paths['/tasks']").exists());
    }

    @Test
    void shouldExposeTaskCreationDictionaryForFrontend() throws Exception {
        mockMvc.perform(get("/dictionaries/task-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pcbTypes[0]").value("BMU板"))
                .andExpect(jsonPath("$.data.pcbTypes[1]").value("BSU板"))
                .andExpect(jsonPath("$.data.pcbTypes[2]").value("分流器板"))
                .andExpect(jsonPath("$.data.pcbTypes[3]").value("高压板"))
                .andExpect(jsonPath("$.data.pcbTypes[4]").value("转接板"))
                .andExpect(jsonPath("$.data.pcbTypes[5]").value("储能板"))
                .andExpect(jsonPath("$.data.pcbTypes[6]").value("其他"))
                .andExpect(jsonPath("$.data.reviewRoles[*].code").value(org.hamcrest.Matchers.contains(
                        "HARDWARE_EXPERT", "EMC_EXPERT", "PCB_EXPERT", "PROCESS_EXPERT", "STRUCTURE_EXPERT")));
    }
}
