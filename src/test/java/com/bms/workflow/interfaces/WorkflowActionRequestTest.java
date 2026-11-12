package com.bms.workflow.interfaces;

import com.bms.common.GlobalExceptionHandler;
import com.bms.common.TraceIdFilter;
import com.bms.file.domain.FileCategory;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.task.domain.TaskStatus;
import com.bms.workflow.application.WorkflowApplicationService;
import com.bms.workflow.domain.WorkflowAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
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
    private final WorkflowApplicationService service = mock(WorkflowApplicationService.class);
    private final ReviewerWhitelistApplicationService whitelist = mock(ReviewerWhitelistApplicationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkflowController(service, new WorkflowTransitionRequestMapper(whitelist)))
            .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new TraceIdFilter()).build();

    @BeforeEach
    void stubTransitionResponse() {
        when(service.transition(eq(1L), any(WorkflowApplicationService.TransitionBatchCommand.class), any(), eq("BMS010")))
                .thenReturn(new WorkflowApplicationService.WorkflowView(1L, TaskStatus.MUTUAL_CHECK_REVIEWING,
                        TaskStatus.PENDING_FINISH, List.of()));
    }

    @Test
    void bindsPendingFinishActionFromMultipartRequest() throws Exception {
        mvc.perform(multipart("/tasks/{taskId}/workflow/transitions", 1L)
                        .header("userId", "BMS010")
                        .param("actions", "PENDING_FINISH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("PENDING_FINISH"));

        ArgumentCaptor<WorkflowApplicationService.TransitionBatchCommand> command =
                ArgumentCaptor.forClass(WorkflowApplicationService.TransitionBatchCommand.class);
        verify(service).transition(eq(1L), command.capture(), any(), eq("BMS010"));
        assertThat(command.getValue().actions()).containsExactly(WorkflowAction.PENDING_FINISH);
    }

    @Test
    void bindsRepeatedActionsAndIndexedFilesInFormOrder() throws Exception {
        var processFile = new MockMultipartFile("files[0].file", "process.zip", "application/zip", new byte[]{1});
        var structureFile = new MockMultipartFile("files[1].file", "structure.zip", "application/zip", new byte[]{2});

        mvc.perform(multipart("/tasks/{taskId}/workflow/transitions", 1L)
                        .file(processFile).file(structureFile)
                        .header("userId", "BMS010")
                        .param("actions", "START_PCB_PROCESS_REVIEW", "START_PCB_STRUCTURE_REVIEW")
                        .param("comment", "同时开启两个评审")
                        .param("files[0].fileCategory", "PCB_PROCESS_REVIEW")
                        .param("files[1].fileCategory", "PCB_STRUCTURE_REVIEW"))
                .andExpect(status().isOk());

        verify(service).transition(1L,
                new WorkflowApplicationService.TransitionBatchCommand(
                        List.of(WorkflowAction.START_PCB_PROCESS_REVIEW, WorkflowAction.START_PCB_STRUCTURE_REVIEW),
                        "同时开启两个评审", List.of()),
                List.of(new WorkflowApplicationService.TransitionFileCommand(FileCategory.PCB_PROCESS_REVIEW, processFile),
                        new WorkflowApplicationService.TransitionFileCommand(FileCategory.PCB_STRUCTURE_REVIEW, structureFile)),
                "BMS010");
    }

    @Test
    void bindsIndexedSchematicReviewerAssignments() throws Exception {
        when(whitelist.findByRoleAndEmployeeNos(ReviewerWhitelistRole.HARDWARE_EXPERT, List.of("BMS002", "BMS003")))
                .thenReturn(List.of(
                        new ReviewerWhitelistApplicationService.WhitelistPersonView("BMS002", "甲", null, null, null),
                        new ReviewerWhitelistApplicationService.WhitelistPersonView("BMS003", "乙", null, null, null)));

        mvc.perform(multipart("/tasks/{taskId}/workflow/transitions", 1L)
                        .header("userId", "BMS010")
                        .param("actions", "START_SCHEMATIC_EXPERT_REVIEW")
                        .param("reviewerAssignments[0].reviewRole", "HARDWARE_EXPERT")
                        .param("reviewerAssignments[0].reviewerEmployeeNos", "BMS002", "BMS003"))
                .andExpect(status().isOk());

        ArgumentCaptor<WorkflowApplicationService.TransitionBatchCommand> command =
                ArgumentCaptor.forClass(WorkflowApplicationService.TransitionBatchCommand.class);
        verify(service).transition(eq(1L), command.capture(), eq(List.of()), eq("BMS010"));
        assertThat(command.getValue().reviewerAssignments()).singleElement().satisfies(assignment -> {
            assertThat(assignment.reviewRole()).isEqualTo(ReviewRole.HARDWARE_EXPERT);
            assertThat(assignment.reviewerEmployeeNos()).containsExactly("BMS002", "BMS003");
        });
    }
}
