package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.file.domain.FileUploadScene;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.domain.ReviewRole;
import com.bms.workflow.application.WorkflowApplicationService;
import com.bms.workflow.domain.WorkflowAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public WorkflowController(WorkflowApplicationService workflowApplicationService) {
        this.workflowApplicationService = workflowApplicationService;
    }

    @PostMapping("/transitions")
    @Operation(summary = "按业务动作推进任务流程", description = "CREATE 不调用本接口：创建任务由 POST /tasks/submit 完成，PCB 任务创建后进入专家评审，原理图任务创建后进入互检单分配。actions 默认只能传一个动作；仅 START_PCB_STRUCTURE_REVIEW 和 START_PCB_PROCESS_REVIEW 可在同一 actions 数组中同时传入，以同一事务登记新文件并开启结构、工艺评审。START_PCB_MATUAL_REVIEW、START_SCHEMATIC_MATUAL_REVIEW、START_SCHEMATIC_EXPERT_REVIEW 需要 assignedRole 和 reviewerIds；START_PCB_MATUAL_ASSIGNMENT、START_SCHEMATIC_EXPERT_ASSIGNMENT、PREPARE_FINISH、FINISH 不接收人员分配。stageFiles 可按动作登记 PROCESS_REVIEW、PCB_REVIEW 或 SCHEMATIC_REVIEW 最新文件。")
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
                                                                      @Valid @RequestBody TransitionRequest request,
                                                                      HttpServletRequest servletRequest) {
        CurrentUser currentUser = CurrentUserHolder.require();
        return ApiResponse.ok(workflowApplicationService.transition(taskId, request.command(), currentUser), traceId(servletRequest));
    }

    @GetMapping("/assignable-reviewers")
    @Operation(summary = "查询当前阶段可分配人员", description = "后端先按任务当前状态推导可分配的任务职责，再按白名单中该职责对应的员工工号查询启用用户账号。每组中的 reviewRole 直接作为 POST /transitions 的 assignedRole，人员的 userId 直接填入 reviewerIds。只有互检分配或原理图专家分配节点可调用。")
    ApiResponse<List<WorkflowApplicationService.AssignableReviewerRoleView>> listAssignableReviewers(@PathVariable long taskId,
                                                                                                         HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.listAssignableReviewers(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "任务流程推进请求")
    record TransitionRequest(@Schema(description = "流程动作数组。默认只传一个动作；仅可同时传 START_PCB_STRUCTURE_REVIEW、START_PCB_PROCESS_REVIEW。CREATE 仅由任务提交接口触发，不可在此传入。", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<WorkflowAction> actions,
                             @Schema(description = "流转意见或说明") String comment,
                             @Schema(description = "随本次动作分配的评审职责。仅 START_PCB_MATUAL_REVIEW、START_SCHEMATIC_MATUAL_REVIEW、START_SCHEMATIC_EXPERT_REVIEW 可传。") ReviewRole assignedRole,
                             @Schema(description = "随本次动作分配的评审人员用户 ID；需要分配人员的动作至少传一名，支持多人。") List<Long> reviewerIds,
                             @Schema(description = "已上传到公司资源服务的阶段文件：工艺/结构评审传 PROCESS_REVIEW；PCB 互检单分配传 PCB_REVIEW；原理图专家分配、原理图准备结束传 SCHEMATIC_REVIEW。") @Valid List<StageFileRequest> stageFiles) {
        WorkflowApplicationService.TransitionBatchCommand command() {
            return new WorkflowApplicationService.TransitionBatchCommand(actions, comment, assignedRole, reviewerIds, stageFileCommands());
        }

        private List<WorkflowApplicationService.StageFileCommand> stageFileCommands() {
            List<WorkflowApplicationService.StageFileCommand> files = stageFiles == null ? List.of() : stageFiles.stream()
                    .map(file -> new WorkflowApplicationService.StageFileCommand(file.scene(), file.fileId(), file.fileName(), file.fileSize(), file.md5(), file.fileKind())).toList();
            return files;
        }

    }

    @Schema(description = "公司资源服务已上传的阶段文件引用")
    record StageFileRequest(@Schema(description = "PROCESS_REVIEW、PCB_REVIEW 或 SCHEMATIC_REVIEW；具体允许值由 actions 决定", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull FileUploadScene scene,
                            @Schema(description = "文件上传接口返回的唯一文件标识", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String fileId,
                            @Schema(description = "原始文件名", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String fileName,
                            @Schema(description = "文件大小，单位字节", requiredMode = Schema.RequiredMode.REQUIRED) @PositiveOrZero long fileSize,
                            @Schema(description = "MD5，可选") String md5,
                            @Schema(description = "工艺/结构文件的内部类型：PROCESS 或 STRUCTURE；不传时由场景生成默认内部关联键") String fileKind) {
    }
}
