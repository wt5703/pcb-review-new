package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.file.domain.FileUploadScene;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.application.ReviewerAssignmentService;
import com.bms.review.domain.ReviewRole;
import com.bms.workflow.application.WorkflowApplicationService;
import com.bms.workflow.domain.WorkflowAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
 * @description 提供统一的任务流程入口：一次请求可编排人员分配、阶段文件登记和状态推进；已结束任务在应用服务层统一拒绝任何重新打开或继续流转请求。
 */
@RestController
@RequestMapping("/tasks/{taskId}/workflow")
@Tag(name = "任务流程", description = "推进 PCB 或原理图评审任务的当前流程节点；已结束任务绝不允许重新打开。")
public class WorkflowController {
    private final WorkflowApplicationService workflowApplicationService;
    private final ReviewerAssignmentService reviewerAssignmentService;

    public WorkflowController(WorkflowApplicationService workflowApplicationService, ReviewerAssignmentService reviewerAssignmentService) {
        this.workflowApplicationService = workflowApplicationService;
        this.reviewerAssignmentService = reviewerAssignmentService;
    }

    @PostMapping("/transitions")
    @Operation(summary = "编排并推进任务流程", description = "同一请求可按 action 一次完成阶段文件登记、人员分配和状态推进，不传版本号。二进制文件必须先调用 POST /files/upload 上传至公司资源服务，再将返回的文件标识放入 stageFiles。START_PCB_OPTIONAL_REVIEW 可附 PROCESS_REVIEW 文件并开启工艺/结构评审；START_PCB_MUTUAL_REVIEW 必须附 PCB_MUTUAL_CHECK 人员；START_SCHEMATIC_MUTUAL_REVIEW 必须附 SCHEMATIC_MUTUAL_CHECK 人员；START_SCHEMATIC_EXPERT_REVIEW 必须附 SCHEMATIC_HARDWARE_EXPERT 或 SCHEMATIC_OTHER_EXPERT 人员；FINISH 直接确认结束。PCB：PCB_PENDING_REVIEW→START_PCB_EXPERT_REVIEW→PCB_EXPERT_REVIEWING→ENTER_PCB_DESIGNER_REPLY→PCB_DESIGNER_REPLYING→START_PCB_OPTIONAL_REVIEW→PCB_OPTIONAL_REVIEWING→ENTER_PCB_OPTIONAL_DESIGNER_REPLY→PCB_OPTIONAL_DESIGNER_REPLYING→PREPARE_PCB_MUTUAL_ASSIGNMENT→PENDING_MUTUAL_ASSIGNMENT→START_PCB_MUTUAL_REVIEW→MUTUAL_REVIEWING→ENTER_PCB_MUTUAL_DESIGNER_REPLY→PCB_MUTUAL_DESIGNER_REPLYING→REQUEST_FINISH→PENDING_FINISH_CONFIRMATION→FINISH。原理图：SCHEMATIC_PENDING_LEADER_ASSIGNMENT→START_SCHEMATIC_MUTUAL_REVIEW→MUTUAL_REVIEWING→ENTER_SCHEMATIC_MUTUAL_DESIGNER_REPLY→SCHEMATIC_MUTUAL_DESIGNER_REPLYING→PREPARE_SCHEMATIC_EXPERT_ASSIGNMENT→SCHEMATIC_PENDING_REVIEW→START_SCHEMATIC_EXPERT_REVIEW→HARDWARE_REVIEWING→ENTER_SCHEMATIC_DESIGNER_REPLY→SCHEMATIC_DESIGNER_REPLYING→REQUEST_FINISH→PENDING_FINISH_CONFIRMATION→FINISH。")
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
                                                                      @Valid @RequestBody TransitionRequest request,
                                                                      HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.transition(taskId, request.toCommand(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/reviewers/me/submit-no-opinion")
    @Operation(summary = "提交本人无评审意见", description = "当前流程节点的评审人员明确提交“无意见”，用于多人评审完成条件计算。人员分配及流程推进统一使用 POST /transitions。")
    ApiResponse<Void> submitNoOpinion(@PathVariable long taskId, HttpServletRequest servletRequest) {
        reviewerAssignmentService.submitNoOpinion(taskId, CurrentUserHolder.require());
        return ApiResponse.ok(null, traceId(servletRequest));
    }

    @GetMapping("/reviewers")
    @Operation(summary = "查询当前阶段已分配人员", description = "按任务和评审职责查询仍有效的分配记录。分配人员请通过 POST /transitions 随对应流程动作提交。")
    ApiResponse<List<ReviewerAssignmentService.ReviewerView>> listReviewers(@PathVariable long taskId,
                                                                               @RequestParam @NotNull ReviewRole role,
                                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewerAssignmentService.listActive(taskId, role, CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "任务流程推进请求")
    record TransitionRequest(@Schema(description = "流程动作。PCB 可用 START_PCB_EXPERT_REVIEW、ENTER_PCB_DESIGNER_REPLY、START_PCB_OPTIONAL_REVIEW、ENTER_PCB_OPTIONAL_DESIGNER_REPLY、PREPARE_PCB_MUTUAL_ASSIGNMENT、START_PCB_MUTUAL_REVIEW、ENTER_PCB_MUTUAL_DESIGNER_REPLY、REQUEST_FINISH、FINISH；原理图可用 START_SCHEMATIC_MUTUAL_REVIEW、ENTER_SCHEMATIC_MUTUAL_DESIGNER_REPLY、PREPARE_SCHEMATIC_EXPERT_ASSIGNMENT、START_SCHEMATIC_EXPERT_REVIEW、ENTER_SCHEMATIC_DESIGNER_REPLY、REQUEST_FINISH、FINISH。每个动作仅可在说明中的前置状态调用。", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull WorkflowAction action,
                             @Schema(description = "流转意见或说明") String comment,
                             @Schema(description = "随本次动作分配的评审职责。仅 START_PCB_MUTUAL_REVIEW、START_SCHEMATIC_MUTUAL_REVIEW、START_SCHEMATIC_EXPERT_REVIEW 可传。") ReviewRole assignedRole,
                             @Schema(description = "随本次动作分配的评审人员用户 ID；需要分配人员的动作至少传一名，支持多人。") List<Long> reviewerIds,
                             @Schema(description = "已上传到公司资源服务的阶段文件。仅 START_PCB_OPTIONAL_REVIEW 可传 PROCESS_REVIEW 文件。") @Valid List<StageFileRequest> stageFiles) {
        WorkflowApplicationService.TransitionCommand toCommand() {
            List<WorkflowApplicationService.StageFileCommand> files = stageFiles == null ? List.of() : stageFiles.stream()
                    .map(file -> new WorkflowApplicationService.StageFileCommand(file.scene(), file.companyFileId(), file.fileName(), file.fileSize(), file.md5(), file.fileKind())).toList();
            return new WorkflowApplicationService.TransitionCommand(action, comment, assignedRole, reviewerIds, files);
        }
    }

    @Schema(description = "公司资源服务已上传的阶段文件引用")
    record StageFileRequest(@Schema(description = "仅允许 PROCESS_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull FileUploadScene scene,
                            @Schema(description = "公司资源服务返回的唯一文件标识或资源路径", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String companyFileId,
                            @Schema(description = "原始文件名", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String fileName,
                            @Schema(description = "文件大小，单位字节", requiredMode = Schema.RequiredMode.REQUIRED) @PositiveOrZero long fileSize,
                            @Schema(description = "MD5，可选") String md5,
                            @Schema(description = "工艺/结构文件的内部类型：PROCESS 或 STRUCTURE；不传时由场景生成默认内部关联键") String fileKind) {
    }
}
