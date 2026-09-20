package com.bms.task.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.task.application.TaskApplicationService;
import com.bms.task.application.MyTaskApplicationService;
import com.bms.task.application.TaskMultipartCreationApplicationService;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.review.domain.ReviewRole;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
import java.time.LocalDate;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 对外提供评审任务草稿创建、提交、列表和详情 REST 接口，负责请求参数校验与响应转换，并将业务处理委托给任务应用服务。
 */


@RestController
@RequestMapping("/tasks")
@Tag(name = "评审任务", description = "创建、提交、查询评审任务，以及获取当前用户待处理任务。所有响应均包含业务数据与 traceId。")
public class TaskController {
    private final TaskApplicationService taskService;
    private final MyTaskApplicationService myTaskApplicationService;
    private final TaskMultipartCreationApplicationService multipartCreationService;

    public TaskController(TaskApplicationService taskService, MyTaskApplicationService myTaskApplicationService,
                          TaskMultipartCreationApplicationService multipartCreationService) {
        this.taskService = taskService;
        this.myTaskApplicationService = myTaskApplicationService;
        this.multipartCreationService = multipartCreationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "创建评审任务草稿（JSON 兼容接口）", description = "兼容已接入系统的纯 JSON 草稿创建。新前端请使用 multipart/form-data 创建接口，将任务表单和评审文件一次提交。")
    ApiResponse<TaskApplicationService.TaskView> create(@Valid @RequestBody CreateTaskRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.create(toCreateCommand(request), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "创建或保存评审任务并上传评审文件", description = "一次提交任务表单 JSON 部分 task 和可多选的 files 文件部分。submit=false 保存草稿；submit=true 保存后立即提交，文件会在同一事务内登记为初始评审文件。")
    ApiResponse<TaskApplicationService.TaskView> createWithFiles(@Valid @RequestPart("task") CreateTaskRequest request,
                                                                   @RequestPart(value = "files", required = false) List<MultipartFile> files,
                                                                   @RequestParam(defaultValue = "false") @Parameter(description = "是否在保存后立即提交。false 保存草稿，true 进入评审流程") boolean submit,
                                                                   HttpServletRequest servletRequest) {
        return ApiResponse.ok(multipartCreationService.create(toCreateCommand(request), files, submit, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/{taskId}/submit")
    @Operation(summary = "提交评审任务", description = "将草稿提交进入相应评审流程。initialFileIds 必须传入设计者已登记的一个或多个评审文件标识。")
    ApiResponse<TaskApplicationService.TaskView> submit(@PathVariable long taskId, @RequestBody SubmitTaskRequest request,
                                                        HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.submit(taskId, request.initialFileIds(), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/{taskId}/draft-files")
    @Operation(summary = "保存草稿评审文件", description = "将一个或多个已登记文件关联到草稿任务，但不推进流程状态；用于创建页点击“保存”时保留多文件选择结果。")
    ApiResponse<TaskApplicationService.TaskView> saveDraftFiles(@PathVariable long taskId, @RequestBody SubmitTaskRequest request,
                                                                  HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.saveDraftFiles(taskId, request.initialFileIds(), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping
    @Operation(summary = "分页查询评审任务", description = "按任务名称、项目名称、设计者姓名（模糊匹配）、评审类型和状态过滤当前用户有权限查看的任务。")
    ApiResponse<TaskApplicationService.TaskPage> list(@RequestParam(required = false) String taskName,
                                                      @RequestParam(required = false) String projectName,
                                                      @RequestParam(required = false) @Parameter(description = "设计者姓名，支持不区分大小写的模糊查询") String designerName,
                                                      @RequestParam(required = false) ReviewType reviewType,
                                                      @RequestParam(required = false) TaskStatus status,
                                                      @RequestParam(required = false) Integer pageNo,
                                                      @RequestParam(required = false) Integer pageSize,
                                                      HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.list(new TaskApplicationService.TaskQuery(taskName, projectName, designerName,
                reviewType, status, pageNo, pageSize), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/my")
    @Operation(summary = "查询我的待办任务", description = "只返回当前流程节点需要当前用户评审、答复、确认、分配或结束的任务，字段与任务列表保持一致，并额外返回 actions。")
    ApiResponse<List<MyTaskApplicationService.MyTaskView>> myTasks(HttpServletRequest servletRequest) {
        return ApiResponse.ok(myTaskApplicationService.list(CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "查询评审任务详情", description = "返回任务完整基础信息、评审角色、期望完成日期和乐观锁版本号。")
    ApiResponse<TaskApplicationService.TaskView> get(@PathVariable long taskId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.get(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }
    private TaskApplicationService.CreateTaskCommand toCreateCommand(CreateTaskRequest request) {
        return new TaskApplicationService.CreateTaskCommand(request.reviewType(), request.taskName(), request.projectName(), request.designerId(),
                defaultDesignerName(request), request.designName(), request.pcbType(), request.expectedCompletedDate() == null ? LocalDate.now() : request.expectedCompletedDate(),
                request.expertLeaderId() == null ? request.designerId() : request.expertLeaderId(), defaultLeaderName(request),
                request.reviewRoles() == null || request.reviewRoles().isEmpty() ? List.of(ReviewRole.PCB_EXPERT) : request.reviewRoles(), request.reviewDescription());
    }
    private String defaultDesignerName(CreateTaskRequest request) { return request.designerName() == null || request.designerName().isBlank() ? "设计者#" + request.designerId() : request.designerName(); }
    private String defaultLeaderName(CreateTaskRequest request) { return request.expertLeaderName() == null || request.expertLeaderName().isBlank() ? "专家/组长#" + request.designerId() : request.expertLeaderName(); }

    @Schema(description = "创建评审任务草稿请求")
    record CreateTaskRequest(
            @Schema(description = "评审类型：PCB 表示 PCB 评审，SCHEMATIC 表示原理图评审", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewType reviewType,
            @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String taskName,
            @Schema(description = "项目名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String projectName,
            @Schema(description = "设计者用户 ID，用于权限校验和后续答复定位", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long designerId,
            @Schema(description = "设计者显示姓名，会冗余保存到任务表并用于任务列表模糊查询", requiredMode = Schema.RequiredMode.REQUIRED) String designerName,
            @Schema(description = "原理图名称或 PCB 名称；随评审类型在页面显示不同标签", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String designName,
            @Schema(description = "PCB 板类型。PCB 评审必填，可选 BMU板、BSU板、分流器板、高压板、转接板、储能板、其他；原理图评审可不传") String pcbType,
            @Schema(description = "期望完成日期，格式 yyyy-MM-dd", example = "2026-09-30", requiredMode = Schema.RequiredMode.REQUIRED) @JsonFormat(pattern = "yyyy-MM-dd") LocalDate expectedCompletedDate,
            @Schema(description = "专家/组长用户 ID，用于后续人员定位", requiredMode = Schema.RequiredMode.REQUIRED) Long expertLeaderId,
            @Schema(description = "专家/组长显示姓名，会冗余保存到任务表", requiredMode = Schema.RequiredMode.REQUIRED) String expertLeaderName,
            @Schema(description = "评审角色，可多选；使用字典接口返回的 ReviewRole 枚举值", requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewRole> reviewRoles,
            @Schema(description = "评审描述，可不传") String reviewDescription) { }
    @Schema(description = "提交评审任务请求")
    record SubmitTaskRequest(@Schema(description = "已通过文件登记接口创建的评审文件 ID 列表，支持多文件", requiredMode = Schema.RequiredMode.REQUIRED) List<Long> initialFileIds) {
        public SubmitTaskRequest { initialFileIds = initialFileIds == null ? List.of() : List.copyOf(initialFileIds); }
    }
}
