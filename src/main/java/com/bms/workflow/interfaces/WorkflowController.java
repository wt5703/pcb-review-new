package com.bms.workflow.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
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
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
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
    private final ReviewerWhitelistApplicationService whitelistApplicationService;

    public WorkflowController(WorkflowApplicationService workflowApplicationService,
                              ReviewerWhitelistApplicationService whitelistApplicationService) {
        this.workflowApplicationService = workflowApplicationService;
        this.whitelistApplicationService = whitelistApplicationService;
    }

    @PostMapping(value = "/tasks/{taskId}/workflow/transitions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "按业务动作推进任务流程", description = """
            <b>传参说明</b><br/>
            所有流程推进均使用 multipart/form-data。CREATE 不调用本接口，创建任务请调用任务提交接口。<br/>
            需要分配人员时，传 reviewerEmployeeNos；不上传文件时无需传 files。<br/>
            流程文件必须按对象传递：files[0].fileCategory + files[0].file，二者表示同一个文件，不能使用分离的文件类型数组。<br/><br/>
            1. 开启工艺/结构评审：可单独传 START_PCB_STRUCTURE_REVIEW 或 START_PCB_PROCESS_REVIEW，也可同时传二者。<br/>
            2. 开启互检单分配：传 START_PCB_MATUAL_ASSIGNMENT。<br/>
            3. 分配互检人员并开启互检：传 START_PCB_MATUAL_REVIEW + reviewerEmployeeNos。<br/>
            4. 分配互检人员并开启互检：传 START_SCHEMATIC_MATUAL_REVIEW + reviewerEmployeeNos。<br/>
            5. 设计者在互检意见闭环后开启原理图评审：传 START_SCHEMATIC_EXPERT_REVIEW；评审人员取创建任务时的角色分配。<br/>
            6. 相关评审意见闭环后，设计者重新上传终版文件并传 PENDING_FINISH，任务进入待结束；组长传 FINISH 结束任务。<br/>
            
            关于文件：开启工艺/结构评审及进入待结束时支持随流程上传；也可先通过文件接口上传终版。
            """)
    ApiResponse<WorkflowApplicationService.WorkflowView> transition(@PathVariable long taskId,
                                                                      @Valid @ModelAttribute TransitionRequest request,
                                                                      @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(workflowApplicationService.transition(taskId,
                request.command(whitelistApplicationService), request.fileCommands(), employeeNo, null), traceId(servletRequest));
    }

    @GetMapping("/workflow/assignable-reviewers")
    @Operation(summary = "按评审类型查询可选专家", description = "PCB任务返回 EMC、PCB 评审白名单；原理图任务返回硬件评审白名单。每人一条，whitelistRoles 为该人员全部有效白名单角色。")
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
        return ApiResponse.ok(workflowApplicationService.listMutualCheckReviewers(reviewType, null, null),
                traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "任务流程推进 multipart/form-data 请求")
    public static class TransitionRequest {
        @Schema(description = "流程动作数组；使用多个 actions 表单字段传递。默认只传一个动作，PCB 工艺和结构评审可同时传两个动作", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty
        private List<WorkflowAction> actions = new ArrayList<>();
        @Schema(description = "流转意见或说明")
        private String comment;
        @Schema(description = "随本次动作分配的评审人员企业工号；使用多个 reviewerEmployeeNos 表单字段传递。需要分配人员的动作至少传一名，支持多人。")
        private List<String> reviewerEmployeeNos = new ArrayList<>();
        @Schema(description = "本次流程上传文件；每项同时传 files[i].fileCategory 和 files[i].file。进入待结束时可上传 PCB_REVIEW 或 SCHEMATIC_REVIEW 终版。")
        @Valid
        private List<TransitionFileRequest> files = new ArrayList<>();

        public List<WorkflowAction> getActions() { return actions; }
        public void setActions(List<WorkflowAction> actions) { this.actions = actions == null ? new ArrayList<>() : actions; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
        public List<String> getReviewerEmployeeNos() { return reviewerEmployeeNos; }
        public void setReviewerEmployeeNos(List<String> reviewerEmployeeNos) { this.reviewerEmployeeNos = reviewerEmployeeNos == null ? new ArrayList<>() : reviewerEmployeeNos; }
        public List<TransitionFileRequest> getFiles() { return files; }
        public void setFiles(List<TransitionFileRequest> files) { this.files = files == null ? new ArrayList<>() : files; }

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

        List<WorkflowApplicationService.TransitionFileCommand> fileCommands() {
            return files.stream().map(file -> new WorkflowApplicationService.TransitionFileCommand(
                    file.getFileCategory(), file.getFile())).toList();
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

    @Schema(description = "单个流程文件；文件类型与文件本身必须在同一个 files[i] 对象中传递")
    public static class TransitionFileRequest {
        @Schema(description = "文件类型：工艺 PCB_PROCESS_REVIEW、结构 PCB_STRUCTURE_REVIEW、PCB终版 PCB_REVIEW、原理图终版 SCHEMATIC_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED)
        @jakarta.validation.constraints.NotNull
        private FileCategory fileCategory;
        @Schema(description = "对应文件", requiredMode = Schema.RequiredMode.REQUIRED)
        @jakarta.validation.constraints.NotNull
        private MultipartFile file;

        public FileCategory getFileCategory() { return fileCategory; }
        public void setFileCategory(FileCategory fileCategory) { this.fileCategory = fileCategory; }
        public MultipartFile getFile() { return file; }
        public void setFile(MultipartFile file) { this.file = file; }
    }
}
