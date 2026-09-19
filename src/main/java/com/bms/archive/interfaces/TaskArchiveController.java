package com.bms.archive.interfaces;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.task.application.TaskApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 提供已结束任务归档快照的只读查询接口，复用任务数据范围校验后才读取快照，不允许通过任务标识绕过授权查看历史详情。
 */
@RestController
@RequestMapping("/tasks/{taskId}/archive")
public class TaskArchiveController {
    private final TaskApplicationService taskApplicationService;
    private final TaskArchiveApplicationService taskArchiveApplicationService;

    public TaskArchiveController(TaskApplicationService taskApplicationService, TaskArchiveApplicationService taskArchiveApplicationService) {
        this.taskApplicationService = taskApplicationService;
        this.taskArchiveApplicationService = taskArchiveApplicationService;
    }

    @GetMapping
    ApiResponse<TaskArchiveApplicationService.ArchiveView> get(@PathVariable long taskId, HttpServletRequest servletRequest) {
        taskApplicationService.get(taskId, CurrentUserHolder.require());
        return ApiResponse.ok(taskArchiveApplicationService.get(taskId), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }
}
