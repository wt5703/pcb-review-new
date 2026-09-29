package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.workflow.application.WorkflowApplicationService;
import com.bms.workflow.domain.WorkflowAction;
import com.bms.workflow.domain.WorkflowAssignmentRole;
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
 * @description 提供统一的任务流程入口：一次请求可编排人员分配和状态推进；文件上传由文件接口独立处理。
 */
@RestController
@RequestMapping
@Tag(name = "任务流程", description = "推进 PCB 或原理图评审任务的当前流程节点；已结束任务绝不允许重新打开。")
public class WorkflowController {
    private final WorkflowApplicationService workflowApplicationService;

    public WorkflowController(WorkflowApplicationService workflowApplicationService) {
        this.workflowApplicationService = workflowApplicationService;
    }

    @PostMapping("/tasks/{taskId}/workflow/transitions")
    @Operation(summary = "按业务动作推进任务流程", description = "CREATE 不调用本接口：创建任务由 POST /tasks/submit 完成，PCB 任务创建后进入专家评审，原理图任务创建后进入互检单分配。actions 默认只能传一个动作；仅 START_PCB_STRUCTURE_REVIEW 和 START_PCB_PROCESS_REVIEW 可在同一 actions 数组中同时传入。文件须先通过 POST /files/upload 上传并绑定任务，本接口只校验所需文件是否已存在并推进状态。开启 PCB 工艺/结构评审前，专家评审意见必须全部通过，且所有已分配的硬件、EMC、PCB 专家均已提出意见或确认无意见；进入 PCB 互检单分配前，必须已有最新 PCB_PROCESS_REVIEW 与 PCB_STRUCTURE_REVIEW 文件。START_PCB_MATUAL_REVIEW、START_SCHEMATIC_MATUAL_REVIEW、START_SCHEMATIC_EXPERT_REVIEW 需要 assignedRole 和 reviewerIds；START_PCB_MATUAL_ASSIGNMENT、START_SCHEMATIC_EXPERT_ASSIGNMENT、PREPARE_FINISH、FINISH 不接收人员分配。")
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
                                                                      @Valid @RequestBody TransitionRequest request,
                                                                      HttpServletRequest servletRequest) {
        CurrentUser currentUser = CurrentUserHolder.require();
        return ApiResponse.ok(workflowApplicationService.transition(taskId, request.command(), currentUser), traceId(servletRequest));
    }

    @GetMapping("/workflow/assignable-reviewers")
    @Operation(summary = "查询当前阶段可分配人员", description = "不传 taskId，仅按 reviewType 和 taskStatus 查询。仅支持三种组合：PCB + MUTUAL_CHECK_PENDING_ASSIGNMENT 返回 PCB_MUTUAL_CHECK 白名单；SCHEMATIC + MUTUAL_CHECK_PENDING_ASSIGNMENT 返回 SCHEMATIC_MUTUAL_CHECK 白名单；SCHEMATIC + SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT 返回 HARDWARE_EXPERT 白名单。返回的 reviewRole 可直接作为 POST /tasks/{taskId}/workflow/transitions 的 assignedRole，人员的 userId 可直接填入 reviewerIds。")
    ApiResponse<List<WorkflowApplicationService.AssignableReviewerRoleView>> listAssignableReviewers(
            @RequestParam @Schema(description = "任务类型：PCB 或 SCHEMATIC", requiredMode = Schema.RequiredMode.REQUIRED) ReviewType reviewType,
            @RequestParam @Schema(description = "当前任务状态；仅支持互检单待分配或原理图待分配硬件专家", requiredMode = Schema.RequiredMode.REQUIRED) TaskStatus taskStatus,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.listAssignableReviewers(reviewType, taskStatus,
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "任务流程推进请求")
    record TransitionRequest(@Schema(description = "流程动作数组。默认只传一个动作；仅可同时传 START_PCB_STRUCTURE_REVIEW、START_PCB_PROCESS_REVIEW。CREATE 仅由任务提交接口触发，不可在此传入。", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<WorkflowAction> actions,
                             @Schema(description = "流转意见或说明") String comment,
                             @Schema(description = "随本次动作分配的流程职责。仅 START_PCB_MATUAL_REVIEW、START_SCHEMATIC_MATUAL_REVIEW、START_SCHEMATIC_EXPERT_REVIEW 可传。") WorkflowAssignmentRole assignedRole,
                             @Schema(description = "随本次动作分配的评审人员用户 ID；需要分配人员的动作至少传一名，支持多人。") List<Long> reviewerIds) {
        WorkflowApplicationService.TransitionBatchCommand command() {
            return new WorkflowApplicationService.TransitionBatchCommand(actions, comment, assignedRole, reviewerIds);
        }
    }
}
