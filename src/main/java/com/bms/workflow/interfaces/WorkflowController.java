package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.workflow.application.WorkflowApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.MediaType;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 任务流程推进
 */
@RestController
@Validated
@RequestMapping
@Tag(name = "任务流程", description = "推进 PCB 或原理图评审任务的当前流程节点")
public class WorkflowController {
    private final WorkflowApplicationService workflowApplicationService;
    private final WorkflowTransitionRequestMapper transitionRequestMapper;

    public WorkflowController(WorkflowApplicationService workflowApplicationService,
                              WorkflowTransitionRequestMapper transitionRequestMapper) {
        this.workflowApplicationService = workflowApplicationService;
        this.transitionRequestMapper = transitionRequestMapper;
    }

    @PostMapping(value = "/tasks/{taskId}/workflow/transitions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "按业务动作推进任务流程", description = """
            <b>传参说明</b><br/>
            所有流程推进均使用 multipart/form-data。CREATE 不调用本接口，创建任务请调用任务提交接口。<br/>
            分配互检人员时传 reviewerEmployeeNos；硬件部开发经理分配原理图专家时传 reviewerAssignments。<br/>
            流程文件必须按对象传递：files[0].fileCategory + files[0].file，二者表示同一个文件，不能使用分离的文件类型数组。<br/><br/>
            1. 开启工艺/结构评审：可单独传 START_PCB_STRUCTURE_REVIEW 或 START_PCB_PROCESS_REVIEW，也可同时传二者。<br/>
            2. 开启互检单分配：传 START_PCB_MATUAL_ASSIGNMENT。<br/>
            3. 分配 PCB 互检人员并开启互检：具备 PCB-LEADER 角色的用户传 START_PCB_MATUAL_REVIEW + reviewerEmployeeNos。<br/>
            4. 分配原理图互检人员并开启互检：任务指定且具备 SCHEMATIC-LEADER 角色的原理图组长传 START_SCHEMATIC_MATUAL_REVIEW + reviewerEmployeeNos。<br/>
            5. 原理图创建任务时已分配 EMC 专家。互检意见闭环后，设计者传 START_SCHEMATIC_EXPERT_ASSIGNMENT，任务进入“硬件专家分配”；随后具备 HARDWARE-MANAGER 角色的用户传 START_SCHEMATIC_EXPERT_REVIEW，并以 reviewerAssignments 指定硬件评审人员，任务才进入原理图评审。<br/>
            6. 设计者推进流程时只校验指定来源意见：开启 PCB 工艺/结构评审校验 PCB_REVIEW；开启 PCB 互检单分配校验 PCB_PROCESS_REVIEW、PCB_STRUCTURE_REVIEW；PCB 进入待结束校验互检意见；原理图进入硬件专家分配校验互检意见，进入待结束校验 SCHEMATIC_REVIEW。<br/>
            7. 相关意见闭环后，设计者重新上传终版文件并传 PENDING_FINISH，任务进入待结束；PCB 组长或任务指定的原理图组长与硬件部经理分别传 FINISH 确认；两人均确认后任务才结束。<br/>
            
            关于文件：开启工艺/结构评审及进入待结束时支持随流程上传；也可先通过文件接口上传终版。
            """)
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
            @Valid @ModelAttribute TransitionRequest request,
            @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        var transition = transitionRequestMapper.map(request);
        var result = workflowApplicationService.transition(taskId, transition.command(), transition.files(), employeeNo);
        return ApiResponse.ok(result, traceId(servletRequest));
    }

    @PostMapping(value = "/tasks/{taskId}/workflow/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "撤回评审任务", description = "仅任务设计者可撤回本人创建的任务。PCB 须处于专家评审，原理图须处于互检单待分配，且任务尚未产生评审意见。填写撤回原因后任务回到待发起，可按原任务 ID 重新提交。")
    ApiResponse<WorkflowApplicationService.WorkflowView> withdraw(@PathVariable long taskId,
            @Valid @RequestBody WorkflowReasonRequest request, @NotBlank @RequestHeader("userId") String employeeNo,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.withdraw(taskId, request.reason(), employeeNo), traceId(servletRequest));
    }

    @PostMapping(value = "/tasks/{taskId}/workflow/terminate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "终止评审任务", description = "仅任务设计者可终止本人创建且未结束、未终止的任务。终止原因必填。任务进入终止状态，原因及操作人记录到流转日志。")
    ApiResponse<WorkflowApplicationService.WorkflowView> terminate(@PathVariable long taskId,
            @Valid @RequestBody WorkflowReasonRequest request, @NotBlank @RequestHeader("userId") String employeeNo,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.terminate(taskId, request.reason(), employeeNo), traceId(servletRequest));
    }

    @PostMapping(value = "/tasks/{taskId}/workflow/return", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "退回评审任务", description = "任务评审专家、硬件部开发经理、原理图组长或 PCB 组长可选择同一评审流程中的任意前序节点，可跨多个节点。退回原因必填，保留文件和意见，退回目标及原因记录到流转日志。")
    ApiResponse<WorkflowApplicationService.WorkflowView> returnTo(@PathVariable long taskId,
            @Valid @RequestBody ReturnTaskRequest request, @NotBlank @RequestHeader("userId") String employeeNo,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.returnTo(taskId, request.targetStatus(), request.reason(), employeeNo),
                traceId(servletRequest));
    }

    @GetMapping("/tasks/{taskId}/workflow/return-targets")
    @Operation(summary = "查询任务可退回节点", description = "按流程顺序返回当前节点之前的所有可退回状态。与退回接口使用相同的操作权限；没有可选节点时返回空列表。")
    ApiResponse<List<TaskStatus>> listReturnTargets(@PathVariable long taskId,
            @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.listReturnTargets(taskId, employeeNo), traceId(servletRequest));
    }

    record WorkflowReasonRequest(
            @Schema(description = "撤回或终止原因，最多 2000 字符", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 2000) String reason) { }

    record ReturnTaskRequest(
            @Schema(description = "退回目标状态，可选择同一评审流程中的任意前序节点", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull TaskStatus targetStatus,
            @Schema(description = "退回原因，最多 2000 字符", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 2000) String reason) { }

    @GetMapping("/workflow/assignable-reviewers")
    @Operation(summary = "按评审类型查询可选专家", description = "PCB任务返回 EMC、PCB 评审白名单；原理图任务返回硬件评审白名单，供硬件部开发经理在互检结束后分配。每人一条，whitelistRoles 为该人员全部有效白名单角色。")
    ApiResponse<List<ReviewerWhitelistApplicationService.AssignableReviewerView>> listAssignableReviewers(
            @RequestParam @Schema(description = "PCB 或 SCHEMATIC", requiredMode = Schema.RequiredMode.REQUIRED) ReviewType reviewType,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.listAssignableReviewers(reviewType), traceId(servletRequest));
    }

    @GetMapping("/workflow/mutual-check-reviewers")
    @Operation(summary = "查询互检单可分配人员", description = "PCB任务返回 PCB 互检单角色，原理图任务返回原理图互检单角色")
    ApiResponse<List<ReviewerWhitelistApplicationService.AssignableReviewerView>> listMutualCheckReviewers(
            @RequestParam @Schema(description = "PCB 或 SCHEMATIC", requiredMode = Schema.RequiredMode.REQUIRED) ReviewType reviewType,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.listMutualCheckReviewers(reviewType),
                traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }
}
