package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.file.domain.FileCategory;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.task.domain.ReviewType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

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
    private final ReviewerWhitelistApplicationService whitelistApplicationService;

    public WorkflowController(WorkflowApplicationService workflowApplicationService,
                              ReviewerWhitelistApplicationService whitelistApplicationService) {
        this.workflowApplicationService = workflowApplicationService;
        this.whitelistApplicationService = whitelistApplicationService;
    }

    @PostMapping(value = "/tasks/{taskId}/workflow/transitions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "按业务动作推进任务流程", description = """
            <b>传参说明</b><br/>
            CREATE 不调用本接口，创建任务请调用任务提交接口。<br/>
            需要分配人员时，传 reviewerEmployeeNos；<br/><br/>
            1. 开启工艺/结构评审：可单独传 START_PCB_STRUCTURE_REVIEW 或 START_PCB_PROCESS_REVIEW，也可同时传二者。<br/>
            2. 开启互检单分配：传 START_PCB_MATUAL_ASSIGNMENT。<br/>
            3. 分配互检人员并开启互检：传 START_PCB_MATUAL_REVIEW + reviewerEmployeeNos。<br/>
            4. 分配互检人员并开启互检：传 START_SCHEMATIC_MATUAL_REVIEW + reviewerEmployeeNos。<br/>
            5. 设计者在互检意见闭环后开启原理图评审：传 START_SCHEMATIC_EXPERT_REVIEW；评审人员取创建任务时的角色分配。<br/>
            6. 设计者准备结束：传 PREPARE_FINISH；组长结束任务：传 FINISH。
            """)
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
                                                                      @Valid @RequestBody TransitionRequest request,
                                                                      HttpServletRequest servletRequest) {
        CurrentUser currentUser = CurrentUserHolder.require();
        return ApiResponse.ok(workflowApplicationService.transition(taskId, request.command(whitelistApplicationService), currentUser), traceId(servletRequest));
    }

    @PostMapping(value = "/tasks/{taskId}/workflow/transitions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传工艺/结构文件并开启评审", description = "仅用于 PCB 工艺或结构评审。command 为与 JSON 流转相同的请求对象；files 与 fileCategories 按下标一一对应，文件类型必须和 START_PCB_PROCESS_REVIEW、START_PCB_STRUCTURE_REVIEW 动作完全匹配。")
    ApiResponse<WorkflowApplicationService.WorkflowView> transitionWithFiles(
            @PathVariable long taskId,
            @Valid @RequestPart("command") TransitionRequest request,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam("fileCategories") List<FileCategory> fileCategories,
            HttpServletRequest servletRequest) {
        if (files == null || fileCategories == null || files.size() != fileCategories.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "files 与 fileCategories 必须一一对应");
        }
        List<WorkflowApplicationService.TransitionFileCommand> transitionFiles = java.util.stream.IntStream.range(0, files.size())
                .mapToObj(index -> new WorkflowApplicationService.TransitionFileCommand(fileCategories.get(index), files.get(index)))
                .toList();
        CurrentUser currentUser = CurrentUserHolder.require();
        return ApiResponse.ok(workflowApplicationService.transition(taskId,
                request.command(whitelistApplicationService), transitionFiles, currentUser), traceId(servletRequest));
    }

    @GetMapping("/workflow/assignable-reviewers")
    @Operation(summary = "按评审类型查询可选专家", description = "仅传 reviewType：PCB 返回 EMC、PCB 评审白名单；原理图返回硬件评审白名单。人员资料从白名单表读取，包含姓名、邮箱、手机号、部门、白名单角色与创建时间。")
    ApiResponse<List<ReviewerWhitelistApplicationService.AssignableReviewerView>> listAssignableReviewers(
            @RequestParam @Schema(description = "PCB 或 SCHEMATIC", requiredMode = Schema.RequiredMode.REQUIRED) ReviewType reviewType,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.listAssignableReviewers(reviewType), traceId(servletRequest));
    }

    @GetMapping("/workflow/mutual-check-reviewers")
    @Operation(summary = "查询互检单可分配人员", description = "仅传 reviewType。PCB 返回 PCB 互检单角色，原理图返回原理图互检单角色；该接口仅用于互检人员分配。")
    ApiResponse<List<ReviewerWhitelistApplicationService.AssignableReviewerView>> listMutualCheckReviewers(
            @RequestParam @Schema(description = "PCB 或 SCHEMATIC", requiredMode = Schema.RequiredMode.REQUIRED) ReviewType reviewType,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.listMutualCheckReviewers(reviewType, CurrentUserHolder.require()),
                traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "任务流程推进请求")
    record TransitionRequest(@Schema(description = "流程动作数组；默认只传一个动作，PCB 工艺和结构评审可同时传两个动作", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<WorkflowAction> actions,
                             @Schema(description = "流转意见或说明") String comment,
                             @Schema(description = "随本次动作分配的评审人员企业工号；需要分配人员的动作至少传一名，支持多人。") List<String> reviewerEmployeeNos) {
        WorkflowApplicationService.TransitionBatchCommand command(ReviewerWhitelistApplicationService whitelistApplicationService) {
            ReviewerWhitelistRole whitelistRole = whitelistRoleForAssignment();
            if (whitelistRole == null) {
                if (reviewerEmployeeNos != null && !reviewerEmployeeNos.isEmpty()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前流程动作不支持分配评审人员");
                }
                return new WorkflowApplicationService.TransitionBatchCommand(actions, comment, List.of());
            }
            List<String> resolvedEmployeeNos = whitelistApplicationService.findByRoleAndEmployeeNos(whitelistRole, reviewerEmployeeNos).stream()
                    .map(ReviewerWhitelistApplicationService.WhitelistPersonView::employeeNo).toList();
            return new WorkflowApplicationService.TransitionBatchCommand(actions, comment, resolvedEmployeeNos);
        }

        private ReviewerWhitelistRole whitelistRoleForAssignment() {
            if (actions == null || actions.size() != 1) {
                return null;
            }
            return switch (actions.get(0)) {
                case START_PCB_MATUAL_REVIEW -> ReviewerWhitelistRole.PCB_MUTUAL_CHECK;
                case START_SCHEMATIC_MATUAL_REVIEW -> ReviewerWhitelistRole.SCHEMATIC_MUTUAL_CHECK;
                default -> null;
            };
        }
    }
}
