package com.bms.task.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.task.application.TaskApplicationService;
import com.bms.task.application.MyTaskApplicationService;
import com.bms.task.application.TaskFileReferenceApplicationService;
import com.bms.file.application.FileApplicationService;
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
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDate;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 对外提供评审任务保存、提交、分页查询和我的待办 REST 接口；任务表单与文件始终在保存或提交请求中一并传入。
 */


@RestController
@RequestMapping("/tasks")
@Tag(name = "评审任务", description = "分页查询任务、查询当前用户待办、保存草稿和提交任务。所有响应均包含业务数据与 traceId。")
public class TaskController {
    private final TaskApplicationService taskService;
    private final MyTaskApplicationService myTaskApplicationService;
    private final TaskFileReferenceApplicationService taskFileReferenceApplicationService;

    public TaskController(TaskApplicationService taskService, MyTaskApplicationService myTaskApplicationService,
                          TaskFileReferenceApplicationService taskFileReferenceApplicationService) {
        this.taskService = taskService;
        this.myTaskApplicationService = myTaskApplicationService;
        this.taskFileReferenceApplicationService = taskFileReferenceApplicationService;
    }

    @PostMapping("/save")
    @Operation(summary = "保存评审任务", description = "唯一的任务保存入口。前端先调用公司资源服务上传文件，再将该服务返回的 fileId 与文件元数据放入 initialFiles；PCB 仅登记文件引用，不接收文件二进制。新建草稿不传 taskId，编辑已有草稿时通过 taskId 查询参数传入。")
    ApiResponse<TaskApplicationService.TaskView> save(@RequestParam(required = false) @Parameter(description = "编辑已有草稿时传任务 ID；新建草稿不传") Long taskId,
                                                       @Valid @RequestBody CreateTaskRequest request,
                                                       HttpServletRequest servletRequest) {
        return ApiResponse.ok(saveOrSubmit(taskId, request, false), traceId(servletRequest));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交评审任务", description = "唯一的任务提交入口。前端先调用公司资源服务上传文件，再将返回的 fileId 与文件元数据放入 initialFiles；PCB 在创建或更新草稿后登记文件信息并提交任务，不接收文件二进制。")
    ApiResponse<TaskApplicationService.TaskView> submit(@RequestParam(required = false) @Parameter(description = "提交已有草稿时传任务 ID；直接新建提交不传") Long taskId,
                                                         @Valid @RequestBody CreateTaskRequest request,
                                                         HttpServletRequest servletRequest) {
        return ApiResponse.ok(saveOrSubmit(taskId, request, true), traceId(servletRequest));
    }

    @GetMapping
    @Operation(summary = "分页查询评审任务", description = "按任务名称、项目名称、设计者姓名（模糊匹配）、评审类型和状态过滤当前用户有权限查看的任务。")
    ApiResponse<TaskApplicationService.TaskPage> list(@RequestParam(required = false) String taskName,
                                                      @RequestParam(required = false) @Parameter(description = "任务 ID；详情页按此参数从分页结果读取单条任务") Long taskId,
                                                      @RequestParam(required = false) String projectName,
                                                      @RequestParam(required = false) @Parameter(description = "设计者姓名，支持不区分大小写的模糊查询") String designerName,
                                                      @RequestParam(required = false) ReviewType reviewType,
                                                      @RequestParam(required = false) TaskStatus status,
                                                      @RequestParam(required = false) Integer pageNo,
                                                      @RequestParam(required = false) Integer pageSize,
                                                      HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.list(new TaskApplicationService.TaskQuery(taskName, projectName, designerName,
                reviewType, status, pageNo, pageSize, taskId), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/my")
    @Operation(summary = "查询我的待办任务", description = "只返回当前流程节点需要当前用户评审、答复、确认、分配或结束的任务，字段与任务列表保持一致，并额外返回 actions。")
    ApiResponse<List<MyTaskApplicationService.MyTaskView>> myTasks(HttpServletRequest servletRequest) {
        return ApiResponse.ok(myTaskApplicationService.list(CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }
    private TaskApplicationService.TaskView saveOrSubmit(Long taskId, CreateTaskRequest request, boolean submit) {
        List<FileApplicationService.FileReferenceCommand> files = toInitialFileReferences(request.initialFiles());
        if (taskId == null) {
            return taskFileReferenceApplicationService.create(toCreateCommand(request), files, submit, CurrentUserHolder.require());
        }
        return taskFileReferenceApplicationService.updateDraft(taskId, toCreateCommand(request), files, submit, CurrentUserHolder.require());
    }
    private List<FileApplicationService.FileReferenceCommand> toInitialFileReferences(List<InitialFileReferenceRequest> files) {
        if (files == null) { return List.of(); }
        return files.stream().map(file -> new FileApplicationService.FileReferenceCommand(file.fileId(), file.fileName(),
                file.fileSize(), file.md5(), null)).toList();
    }
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
            @Schema(description = "评审描述，可不传") String reviewDescription,
            @Schema(description = "前端上传至公司资源服务后返回的初始评审文件引用；PCB 仅存储 fileId 和元数据") List<@Valid InitialFileReferenceRequest> initialFiles) { }

    @Schema(description = "公司资源服务文件引用")
    record InitialFileReferenceRequest(
            @Schema(description = "公司资源服务上传返回的唯一文件标识", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String fileId,
            @Schema(description = "原始文件名", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String fileName,
            @Schema(description = "文件大小（字节）", requiredMode = Schema.RequiredMode.REQUIRED) @jakarta.validation.constraints.PositiveOrZero long fileSize,
            @Schema(description = "MD5，可选") String md5) { }
}
