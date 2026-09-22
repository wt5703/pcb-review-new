package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
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
    @Operation(summary = "按业务动作推进任务流程", description = "创建任务使用 POST /tasks/save 或 /tasks/submit，不调用本接口；互检单/原理图文件二进制上传使用 POST /files/upload，也不调用本接口。本接口只负责已创建任务的状态推进：START_PCB_MUTUAL_REVIEW、START_SCHEMATIC_MUTUAL_REVIEW 用于分配互检组员并进入互检；START_SCHEMATIC_EXPERT_REVIEW 用于分配原理图专家并进入评审；START_PCB_OPTIONAL_REVIEW 用于设计者上传工艺或结构文件后开启对应评审；FINISH 用于管理员在结束确认节点手动结束。其余 action 仅推进当前无人员、无文件的常规节点。")
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
                                                                      @Valid @RequestBody TransitionRequest request,
                                                                      HttpServletRequest servletRequest) {
        CurrentUser currentUser = CurrentUserHolder.require();
        return ApiResponse.ok(executeAction(taskId, request, currentUser), traceId(servletRequest));
    }

    /**
     * 根据前端实际发起的业务动作选择对应参数组合。这里不计算目标状态，目标状态、权限和前置条件仍由应用服务统一校验。
     */
    private WorkflowApplicationService.WorkflowView executeAction(long taskId, TransitionRequest request, CurrentUser currentUser) {
        return switch (request.action()) {
            case SUBMIT_NO_OPINION -> submitNoOpinion(taskId, request, currentUser);
            case START_PCB_MUTUAL_REVIEW, START_SCHEMATIC_MUTUAL_REVIEW -> assignMutualCheckReviewers(taskId, request, currentUser);
            case START_SCHEMATIC_EXPERT_REVIEW -> assignSchematicExperts(taskId, request, currentUser);
            case START_PCB_OPTIONAL_REVIEW -> startPcbProcessOrStructureReview(taskId, request, currentUser);
            case FINISH -> finishTask(taskId, request, currentUser);
            default -> advanceOrdinaryNode(taskId, request, currentUser);
        };
    }

    private WorkflowApplicationService.WorkflowView assignMutualCheckReviewers(long taskId, TransitionRequest request, CurrentUser currentUser) {
        return workflowApplicationService.transition(taskId, request.assignmentCommand(), currentUser);
    }

    private WorkflowApplicationService.WorkflowView submitNoOpinion(long taskId, TransitionRequest request, CurrentUser currentUser) {
        return workflowApplicationService.transition(taskId, request.noOpinionCommand(), currentUser);
    }

    private WorkflowApplicationService.WorkflowView assignSchematicExperts(long taskId, TransitionRequest request, CurrentUser currentUser) {
        return workflowApplicationService.transition(taskId, request.assignmentCommand(), currentUser);
    }

    private WorkflowApplicationService.WorkflowView startPcbProcessOrStructureReview(long taskId, TransitionRequest request, CurrentUser currentUser) {
        return workflowApplicationService.transition(taskId, request.stageFileCommand(), currentUser);
    }

    private WorkflowApplicationService.WorkflowView finishTask(long taskId, TransitionRequest request, CurrentUser currentUser) {
        return workflowApplicationService.transition(taskId, request.finishCommand(), currentUser);
    }

    private WorkflowApplicationService.WorkflowView advanceOrdinaryNode(long taskId, TransitionRequest request, CurrentUser currentUser) {
        return workflowApplicationService.transition(taskId, request.ordinaryCommand(), currentUser);
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
    record TransitionRequest(@Schema(description = "流程动作。评审人无意见提交：SUBMIT_NO_OPINION；分配互检组员：START_PCB_MUTUAL_REVIEW 或 START_SCHEMATIC_MUTUAL_REVIEW；分配原理图专家：START_SCHEMATIC_EXPERT_REVIEW；设计者上传工艺/结构文件后开启评审：START_PCB_OPTIONAL_REVIEW；管理员结束：FINISH。其他枚举仅用于无人员、无文件的常规节点推进。", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull WorkflowAction action,
                             @Schema(description = "流转意见或说明") String comment,
                             @Schema(description = "随本次动作分配的评审职责。仅 START_PCB_MUTUAL_REVIEW、START_SCHEMATIC_MUTUAL_REVIEW、START_SCHEMATIC_EXPERT_REVIEW 可传。") ReviewRole assignedRole,
                             @Schema(description = "随本次动作分配的评审人员用户 ID；需要分配人员的动作至少传一名，支持多人。") List<Long> reviewerIds,
                             @Schema(description = "已上传到公司资源服务的阶段文件。仅 START_PCB_OPTIONAL_REVIEW 可传 PROCESS_REVIEW 文件。") @Valid List<StageFileRequest> stageFiles) {
        WorkflowApplicationService.TransitionCommand assignmentCommand() {
            if (stageFiles != null && !stageFiles.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分配评审人员时不能同时登记阶段文件");
            }
            return new WorkflowApplicationService.TransitionCommand(action, comment, assignedRole, reviewerIds, List.of());
        }

        WorkflowApplicationService.TransitionCommand stageFileCommand() {
            if (assignedRole != null || (reviewerIds != null && !reviewerIds.isEmpty())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "开启工艺或结构评审时不能同时分配评审人员");
            }
            return new WorkflowApplicationService.TransitionCommand(action, comment, null, List.of(), stageFileCommands());
        }

        WorkflowApplicationService.TransitionCommand finishCommand() {
            requireNoExtraPayload("手动结束流程");
            return new WorkflowApplicationService.TransitionCommand(action, comment, null, List.of(), List.of());
        }

        WorkflowApplicationService.TransitionCommand noOpinionCommand() {
            requireNoExtraPayload("提交无意见");
            return new WorkflowApplicationService.TransitionCommand(action, comment, null, List.of(), List.of());
        }

        WorkflowApplicationService.TransitionCommand ordinaryCommand() {
            requireNoExtraPayload("常规节点推进");
            return new WorkflowApplicationService.TransitionCommand(action, comment, null, List.of(), List.of());
        }

        private List<WorkflowApplicationService.StageFileCommand> stageFileCommands() {
            List<WorkflowApplicationService.StageFileCommand> files = stageFiles == null ? List.of() : stageFiles.stream()
                    .map(file -> new WorkflowApplicationService.StageFileCommand(file.scene(), file.companyFileId(), file.fileName(), file.fileSize(), file.md5(), file.fileKind())).toList();
            return files;
        }

        private void requireNoExtraPayload(String actionName) {
            if (assignedRole != null || (reviewerIds != null && !reviewerIds.isEmpty()) || (stageFiles != null && !stageFiles.isEmpty())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, actionName + "不接收人员分配或阶段文件参数");
            }
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
