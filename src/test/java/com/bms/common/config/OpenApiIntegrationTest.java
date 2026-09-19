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
}
