package com.bms.workflow.interfaces;

import com.bms.common.GlobalExceptionHandler;
import com.bms.common.TraceIdFilter;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.task.domain.TaskStatus;
import com.bms.workflow.application.WorkflowApplicationService;
import com.bms.workflow.domain.WorkflowAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowActionRequestTest {
    @Test
    void bindsPendingFinishActionFromMultipartRequest() throws Exception {
        WorkflowApplicationService service = mock(WorkflowApplicationService.class);
        ReviewerWhitelistApplicationService whitelist = mock(ReviewerWhitelistApplicationService.class);
        when(service.transition(eq(1L), any(WorkflowApplicationService.TransitionBatchCommand.class), any(), any(), any()))
                .thenReturn(new WorkflowApplicationService.WorkflowView(1L, TaskStatus.MUTUAL_CHECK_REVIEWING,
                        TaskStatus.PENDING_FINISH, List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkflowController(service, whitelist))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new TraceIdFilter()).build();

        mvc.perform(multipart("/tasks/{taskId}/workflow/transitions", 1L)
                        .header("userId", "BMS010")
                        .param("actions", "PENDING_FINISH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("PENDING_FINISH"));

        ArgumentCaptor<WorkflowApplicationService.TransitionBatchCommand> command =
                ArgumentCaptor.forClass(WorkflowApplicationService.TransitionBatchCommand.class);
        verify(service).transition(eq(1L), command.capture(), any(), eq("BMS010"), eq(null));
        assertThat(command.getValue().actions()).containsExactly(WorkflowAction.PENDING_FINISH);
    }
}
