package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.workflow.application.WorkflowApplicationService;
import com.bms.workflow.domain.WorkflowAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 提供统一的任务流程推进接口，调用方必须提交预期任务版本；已结束任务在应用服务层统一拒绝任何重新打开或继续流转请求。
 */
@RestController
@RequestMapping("/tasks/{taskId}/workflow")
@Tag(name = "任务流程", description = "推进 PCB 或原理图评审任务的当前流程节点；已结束任务绝不允许重新打开。")
public class WorkflowController {
    private final WorkflowApplicationService workflowApplicationService;

    public WorkflowController(WorkflowApplicationService workflowApplicationService) {
        this.workflowApplicationService = workflowApplicationService;
    }

    @PostMapping("/transitions")
    @Operation(summary = "推进任务流程", description = "按 action 推进当前任务状态，必须传递详情接口返回的 version 以避免并发覆盖。")
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
                                                                      @Valid @RequestBody TransitionRequest request,
                                                                      HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.transition(taskId, request.action(), request.version(), request.comment(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "任务流程推进请求")
    record TransitionRequest(@Schema(description = "流程动作枚举", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull WorkflowAction action,
                             @Schema(description = "任务详情返回的乐观锁版本号", requiredMode = Schema.RequiredMode.REQUIRED) @PositiveOrZero long version,
                             @Schema(description = "流转意见或说明") String comment) {
    }
}
