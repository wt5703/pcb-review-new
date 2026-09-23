package com.bms.archive.interfaces;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.task.application.TaskApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "任务归档", description = "读取已结束评审任务的不可变归档快照，返回流程节点和阶段文件。")
public class TaskArchiveController {
    private final TaskApplicationService taskApplicationService;
    private final TaskArchiveApplicationService taskArchiveApplicationService;

    public TaskArchiveController(TaskApplicationService taskApplicationService, TaskArchiveApplicationService taskArchiveApplicationService) {
        this.taskApplicationService = taskApplicationService;
        this.taskArchiveApplicationService = taskArchiveApplicationService;
    }

    @GetMapping
    @Operation(summary = "查询任务归档记录", description = "仅可读取已结束任务的归档快照；返回流程节点时间/节点名称/操作人姓名/流转意见，以及阶段文件。不返回任务快照、评审人员、评审意见或邮件记录。")
    ApiResponse<TaskArchiveApplicationService.ArchiveView> get(@PathVariable long taskId, HttpServletRequest servletRequest) {
        taskApplicationService.get(taskId, CurrentUserHolder.require());
        return ApiResponse.ok(taskArchiveApplicationService.get(taskId), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }
}
