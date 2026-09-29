package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.application.ReviewerWhitelistDirectoryApplicationService;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.workflow.application.WorkflowApplicationService;
import com.bms.workflow.domain.WorkflowAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 任务流程推进
 */
@RestController
@RequestMapping
@Tag(name = "任务流程", description = "推进 PCB 或原理图评审任务的当前流程节点；已结束任务绝不允许重新打开。")
public class WorkflowController {
    private final WorkflowApplicationService workflowApplicationService;
    private final ReviewerWhitelistDirectoryApplicationService whitelistDirectoryApplicationService;

    public WorkflowController(WorkflowApplicationService workflowApplicationService,
                              ReviewerWhitelistDirectoryApplicationService whitelistDirectoryApplicationService) {
        this.workflowApplicationService = workflowApplicationService;
        this.whitelistDirectoryApplicationService = whitelistDirectoryApplicationService;
    }

    @PostMapping("/tasks/{taskId}/workflow/transitions")
    @Operation(summary = "按业务动作推进任务流程", description = """
            <b>传参说明</b><br/>
            CREATE 不调用本接口，创建任务请调用任务提交接口。<br/>
            需要分配人员时，传 reviewerEmployeeNos；<br/><br/>
            1. 开启工艺/结构评审：可单独传 START_PCB_STRUCTURE_REVIEW 或 START_PCB_PROCESS_REVIEW，也可同时传二者。<br/>
            2. 开启互检单分配：传 START_PCB_MATUAL_ASSIGNMENT。<br/>
            3. 分配互检人员并开启互检：传 START_PCB_MATUAL_REVIEW + reviewerEmployeeNos。<br/>
            4. 分配互检人员并开启互检：传 START_SCHEMATIC_MATUAL_REVIEW + reviewerEmployeeNos。<br/>
            5. 开启硬件专家分配：传 START_SCHEMATIC_EXPERT_ASSIGNMENT。<br/>
            6. 分配硬件专家并开启原理图评审：传 START_SCHEMATIC_EXPERT_REVIEW + reviewerEmployeeNos。<br/>
            7. 设计者准备结束：传 PREPARE_FINISH；组长结束任务：传 FINISH。
            """)
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
                                                                      @Valid @RequestBody TransitionRequest request,
                                                                      HttpServletRequest servletRequest) {
        CurrentUser currentUser = CurrentUserHolder.require();
        return ApiResponse.ok(workflowApplicationService.transition(taskId, request.command(whitelistDirectoryApplicationService), currentUser), traceId(servletRequest));
    }

    @GetMapping("/workflow/assignable-reviewers")
    @Operation(summary = "查询当前阶段可分配人员", description = "查询当前阶段可分配人员")
    ApiResponse<List<ReviewerWhitelistApplicationService.AssignableReviewerView>> listAssignableReviewers(
            @RequestParam @Schema(description = "PCB 或 SCHEMATIC", requiredMode = Schema.RequiredMode.REQUIRED) ReviewType reviewType,
            @RequestParam @Schema(description = "互检单分配 传 MUTUAL_CHECK_PENDING_ASSIGNMENT   硬件专家分配 传 SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT", requiredMode = Schema.RequiredMode.REQUIRED) TaskStatus taskStatus,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.listAssignableReviewers(reviewType, taskStatus,
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "任务流程推进请求")
    record TransitionRequest(@Schema(description = "流程动作数组；默认只传一个动作，PCB 工艺和结构评审可同时传两个动作", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<WorkflowAction> actions,
                             @Schema(description = "流转意见或说明") String comment,
                             @Schema(description = "随本次动作分配的评审人员企业工号；需要分配人员的动作至少传一名，支持多人。") List<String> reviewerEmployeeNos) {
        WorkflowApplicationService.TransitionBatchCommand command(ReviewerWhitelistDirectoryApplicationService whitelistDirectoryApplicationService) {
            ReviewerWhitelistRole whitelistRole = whitelistRoleForAssignment();
            if (whitelistRole == null) {
                if (reviewerEmployeeNos != null && !reviewerEmployeeNos.isEmpty()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持分配评审人员");
                }
                return new WorkflowApplicationService.TransitionBatchCommand(actions, comment, List.of());
            }
            List<Long> reviewerIds = whitelistDirectoryApplicationService.findByRoleAndEmployeeNos(whitelistRole, reviewerEmployeeNos).stream()
                    .map(ReviewerWhitelistDirectoryApplicationService.WhitelistPersonView::id).toList();
            return new WorkflowApplicationService.TransitionBatchCommand(actions, comment, reviewerIds);
        }

        private ReviewerWhitelistRole whitelistRoleForAssignment() {
            if (actions == null || actions.size() != 1) {
                return null;
            }
            return switch (actions.get(0)) {
                case START_PCB_MATUAL_REVIEW -> ReviewerWhitelistRole.PCB_MUTUAL_CHECK;
                case START_SCHEMATIC_MATUAL_REVIEW -> ReviewerWhitelistRole.SCHEMATIC_MUTUAL_CHECK;
                case START_SCHEMATIC_EXPERT_REVIEW -> ReviewerWhitelistRole.HARDWARE_EXPERT;
                default -> null;
            };
        }
    }
}
