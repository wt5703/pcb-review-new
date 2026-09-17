package com.leapmotor.pcbreview.task.interfaces;

import com.leapmotor.pcbreview.common.ApiResponse;
import com.leapmotor.pcbreview.common.TraceIdFilter;
import com.leapmotor.pcbreview.identity.application.CurrentUserHolder;
import com.leapmotor.pcbreview.task.application.TaskApplicationService;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * @date 2026-09-14
 * @description 对外提供评审任务草稿创建、提交、列表和详情 REST 接口，负责请求参数校验与响应转换，并将业务处理委托给任务应用服务。
 */


@RestController
@RequestMapping("/tasks")
public class TaskController {
    private final TaskApplicationService taskService;

    public TaskController(TaskApplicationService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    ApiResponse<TaskApplicationService.TaskView> create(@Valid @RequestBody CreateTaskRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.create(new TaskApplicationService.CreateTaskCommand(request.reviewType(), request.taskName(),
                request.projectName(), request.designerId(), request.designName(), request.pcbType()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/{taskId}/submit")
    ApiResponse<TaskApplicationService.TaskView> submit(@PathVariable long taskId, @RequestBody SubmitTaskRequest request,
                                                        HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.submit(taskId, request.initialFileIds(), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping
    ApiResponse<TaskApplicationService.TaskPage> list(@RequestParam(required = false) String taskName,
                                                      @RequestParam(required = false) String projectName,
                                                      @RequestParam(required = false) Long designerId,
                                                      @RequestParam(required = false) ReviewType reviewType,
                                                      @RequestParam(required = false) TaskStatus status,
                                                      @RequestParam(required = false) Integer pageNo,
                                                      @RequestParam(required = false) Integer pageSize,
                                                      HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.list(new TaskApplicationService.TaskQuery(taskName, projectName, designerId,
                reviewType, status, pageNo, pageSize), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/{taskId}")
    ApiResponse<TaskApplicationService.TaskView> get(@PathVariable long taskId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskService.get(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }

    record CreateTaskRequest(@NotNull ReviewType reviewType, @NotBlank String taskName, @NotBlank String projectName,
                             @NotNull Long designerId, @NotBlank String designName, String pcbType) { }
    record SubmitTaskRequest(List<Long> initialFileIds) {
        public SubmitTaskRequest { initialFileIds = initialFileIds == null ? List.of() : List.copyOf(initialFileIds); }
    }
}
