package com.bms.identity.interfaces;

import com.bms.common.GlobalExceptionHandler;
import com.bms.common.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CurrentUserControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CurrentUserController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

    @Test
    void returnsIdentityFromHeaders() throws Exception {
        mvc.perform(get("/identity/current-user")
                        .header("userId", "2653376")
                        .header("userName", "王涛"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeNo").value("2653376"))
                .andExpect(jsonPath("$.data.displayName").value("王涛"));
    }

    @Test
    void rejectsMissingIdentityHeaders() throws Exception {
        mvc.perform(get("/identity/current-user").header("userId", "2653376"))
                .andExpect(status().isBadRequest());
    }
}
