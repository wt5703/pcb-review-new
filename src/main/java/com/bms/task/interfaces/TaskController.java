package com.bms.task.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.task.application.TaskApplicationService;
import com.bms.task.application.MyTaskApplicationService;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.review.domain.ReviewRole;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.time.LocalDate;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 对外提供评审任务保存、提交、分页查询和我的待办接口
 */


@RestController
@Validated
@RequestMapping("/tasks")
@Tag(name = "评审任务", description = "分页查询任务、查询当前用户待办、保存草稿和提交任务。")
public class TaskController {
    private final TaskApplicationService taskService;
    private final MyTaskApplicationService myTaskApplicationService;
    private final ReviewerWhitelistApplicationService whitelistApplicationService;

    public TaskController(TaskApplicationService taskService, MyTaskApplicationService myTaskApplicationService,
                          ReviewerWhitelistApplicationService whitelistApplicationService) {
        this.taskService = taskService;
        this.myTaskApplicationService = myTaskApplicationService;
        this.whitelistApplicationService = whitelistApplicationService;
    }

    @PostMapping("/save")
    @Operation(summary = "保存评审任务", description = "新建草稿不传 taskId，编辑已有草稿时通过 taskId 查询参数传入")
    ApiResponse<TaskApplicationService.TaskView> saveTaskDraft(@RequestParam(required = false) @Parameter(description = "编辑已有草稿时传任务 ID；新建草稿不传") Long taskId,
                                                                @Valid @RequestBody CreateTaskRequest request,
                                                                @NotBlank @RequestHeader("userId") String employeeNo, @NotBlank @RequestHeader("userName") String userName, HttpServletRequest servletRequest) {
        return ApiResponse.ok(saveTaskForm(taskId, request, false, employeeNo, userName), getTraceId(servletRequest));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交评审任务", description = "提交任务，任务会进入流程的下一个节点")
    ApiResponse<TaskApplicationService.TaskView> submitTask(@RequestParam(required = false) @Parameter(description = "提交已有草稿时传任务 ID；直接新建提交不传") Long taskId,
                                                             @Valid @RequestBody CreateTaskRequest request,
                                                             @NotBlank @RequestHeader("userId") String employeeNo, @NotBlank @RequestHeader("userName") String userName, HttpServletRequest servletRequest) {
        return ApiResponse.ok(saveTaskForm(taskId, request, true, employeeNo, userName), getTraceId(servletRequest));
    }

    @GetMapping
    @Operation(summary = "分页查询评审任务", description = "使用 URL 查询参数筛选当前用户有权限查看的任务。keyword 同时匹配项目名称、任务名称和设计者名称；任务状态可多选。请求页码参数为 pageNum，响应分页字段为 pageNum、pageSize、total。")
    ApiResponse<TaskApplicationService.TaskPage> list(
            @RequestParam(required = false) @Parameter(description = "关键字，同时模糊匹配项目名称、任务名称和设计者名称") String keyword,
            @RequestParam(required = false) @Parameter(description = "评审类型：PCB 或 SCHEMATIC") ReviewType reviewType,
            @RequestParam(required = false) @Parameter(description = "任务状态，可传多个同名参数；PENDING_FINISH 表示待结束，FINISHED 表示已结束") List<TaskStatus> statuses,
            @RequestParam(name = "pageNum", defaultValue = "1") @Parameter(description = "页码，从 1 开始") Integer pageNum,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页条数，最大 100") Integer pageSize,
            @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.listTasks(new TaskApplicationService.TaskQuery(keyword, reviewType, statuses, pageNum, pageSize, null),
                employeeNo, null), getTraceId(servletRequest));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "查询评审任务详情", description = "按任务 ID 返回当前用户有权查看的一条任务基础信息和流程流转记录；用于任务详情页加载")
    ApiResponse<TaskApplicationService.TaskView> detail(@PathVariable long taskId, @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.getTaskDetail(taskId, employeeNo, null), getTraceId(servletRequest));
    }

    @GetMapping("/my")
    @Operation(summary = "查询我的待办任务", description = "返回当前流程节点需要当前用户评审、答复、确认、分配或结束的任务")
    ApiResponse<List<MyTaskApplicationService.MyTaskView>> myTasks(@NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(myTaskApplicationService.list(employeeNo, null), getTraceId(servletRequest));
    }

    private String getTraceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    /**
     * 将页面表单转换为任务命令，并根据入口决定“只保存草稿”还是“保存后立即提交”。
     */
    private TaskApplicationService.TaskView saveTaskForm(Long taskId, CreateTaskRequest request, boolean submitAfterSave, String employeeNo, String userName) {
        List<String> pendingFileIds = extractPendingFileIds(request.files());
        TaskApplicationService.CreateTaskCommand command = toTaskCommand(request, employeeNo, userName);
        if (taskId == null) {
            return taskService.saveNewTask(command, pendingFileIds, submitAfterSave, employeeNo, userName);
        }
        return taskService.saveExistingTask(taskId, command, pendingFileIds, submitAfterSave, employeeNo, userName);
    }

    private List<String> extractPendingFileIds(List<String> files) {
        if (files == null) {
            return List.of();
        }
        return List.copyOf(files);
    }

    private TaskApplicationService.CreateTaskCommand toTaskCommand(CreateTaskRequest request, String employeeNo, String userName) {
        if (request.reviewType() == ReviewType.SCHEMATIC
                && (request.expertLeaderEmployeeNo() == null || request.expertLeaderEmployeeNo().isBlank())) {
            throw new IllegalArgumentException("原理图评审必须选择组长并传入组长工号");
        }
        ReviewerWhitelistApplicationService.WhitelistPersonView leader = request.expertLeaderEmployeeNo() == null || request.expertLeaderEmployeeNo().isBlank()
                ? new ReviewerWhitelistApplicationService.WhitelistPersonView(employeeNo, userName, null, null, null)
                : whitelistApplicationService.findByEmployeeNo(request.expertLeaderEmployeeNo());
        return new TaskApplicationService.CreateTaskCommand(request.reviewType(), request.taskName(), request.projectName(), employeeNo,
                userName, request.designName(), request.pcbType(), request.expectedCompletedDate() == null ? LocalDate.now() : request.expectedCompletedDate(),
                leader.employeeNo(), leader.displayName(),
                request.reviewRoles() == null || request.reviewRoles().isEmpty() ? List.of(ReviewRole.PCB_EXPERT) : request.reviewRoles(),
                (request.reviewerAssignments() == null ? List.<ReviewerAssignmentRequest>of() : request.reviewerAssignments()).stream()
                        .map(assignment -> new TaskReviewerAssignment(assignment.reviewRole(), whitelistApplicationService
                                .findByRoleAndEmployeeNos(ReviewerWhitelistRole.fromReviewRole(assignment.reviewRole()), assignment.reviewerEmployeeNos()).stream()
                                .map(ReviewerWhitelistApplicationService.WhitelistPersonView::employeeNo).toList())).toList(),
                request.reviewDescription());
    }

    @Schema(description = "创建评审任务草稿请求")
    record CreateTaskRequest(
            @Schema(description = "评审类型：PCB 表示 PCB 评审，SCHEMATIC 表示原理图评审", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewType reviewType,
            @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String taskName,
            @Schema(description = "项目名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String projectName,
            @Schema(description = "原理图名称或 PCB 名称；随评审类型在页面显示不同标签", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String designName,
            @Schema(description = "PCB 板类型。PCB 评审必填，可选 BMU板、BSU板、分流器板、高压板、转接板、储能板、其他；原理图评审可不传") String pcbType,
            @Schema(description = "期望完成日期，格式 yyyy-MM-dd", example = "2026-09-30", requiredMode = Schema.RequiredMode.REQUIRED) @JsonFormat(pattern = "yyyy-MM-dd") LocalDate expectedCompletedDate,
            @Schema(description = "原理图组长的企业工号；PCB 任务不需要传，默认使用当前登录设计者", example = "10002") String expertLeaderEmployeeNo,
            @Schema(description = "评审角色，可多选；PCB 仅支持硬件、EMC、PCB、工艺、结构，原理图仅支持硬件、EMC、PCB", requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewRole> reviewRoles,
            @Schema(description = "每个已选评审角色对应的白名单专家；每个角色至少选择一人", requiredMode = Schema.RequiredMode.REQUIRED) List<@Valid ReviewerAssignmentRequest> reviewerAssignments,
            @Schema(description = "评审描述，可不传") String reviewDescription,
            @Schema(description = "前端调用文件上传接口后返回的文件 UUID 列表；后端按 UUID 读取并保存文件元数据，同一任务不可重复传入相同 UUID") List<@NotBlank String> files) { }

    @Schema(description = "一个评审角色及其已选专家")
    record ReviewerAssignmentRequest(
            @Schema(description = "评审角色", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewRole reviewRole,
            @Schema(description = "该角色下的白名单专家企业工号，至少一人", requiredMode = Schema.RequiredMode.REQUIRED) List<@NotBlank String> reviewerEmployeeNos) { }
}
