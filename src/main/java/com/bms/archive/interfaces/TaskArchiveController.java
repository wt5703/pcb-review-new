package com.bms.archive.interfaces;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.archive.application.TaskArchiveOpinionExportApplicationService;
import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.task.application.TaskApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 提供已结束任务归档快照的只读查询接口，不允许通过任务标识绕过授权查看历史详情
 */
@RestController
@RequestMapping("/tasks/{taskId}/archive")
@Tag(name = "任务归档", description = "读取已结束评审任务的不可变归档快照，返回流程节点、阶段文件和邮件投递记录。")
public class TaskArchiveController {
    private final TaskApplicationService taskApplicationService;
    private final TaskArchiveApplicationService taskArchiveApplicationService;
    private final TaskArchiveOpinionExportApplicationService taskArchiveOpinionExportApplicationService;

    public TaskArchiveController(TaskApplicationService taskApplicationService, TaskArchiveApplicationService taskArchiveApplicationService,
                                 TaskArchiveOpinionExportApplicationService taskArchiveOpinionExportApplicationService) {
        this.taskApplicationService = taskApplicationService;
        this.taskArchiveApplicationService = taskArchiveApplicationService;
        this.taskArchiveOpinionExportApplicationService = taskArchiveOpinionExportApplicationService;
    }

    @GetMapping
    @Operation(summary = "查询任务归档记录", description = "仅可读取已结束任务的归档快照；返回流程节点时间/节点名称/操作人姓名/流转意见、阶段文件及邮件投递记录")
    ApiResponse<TaskArchiveApplicationService.ArchiveView> get(@PathVariable long taskId, HttpServletRequest servletRequest) {
        taskApplicationService.get(taskId, CurrentUserHolder.require());
        return ApiResponse.ok(taskArchiveApplicationService.get(taskId), traceId(servletRequest));
    }

    @GetMapping("/export")
    @Operation(summary = "导出归档评审意见 Excel", description = "仅支持已结束且已归档任务。按评审角色拆分工作表；一条意见存在多轮设计者答复时，每轮答复导出一行。仅导出专家、工艺和结构评审意见，不包含互检单意见。")
    ResponseEntity<byte[]> exportOpinions(@PathVariable long taskId) {
        taskApplicationService.get(taskId, CurrentUserHolder.require());
        taskArchiveApplicationService.get(taskId);
        TaskArchiveOpinionExportApplicationService.ExportedExcel excel = taskArchiveOpinionExportApplicationService.export(taskId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(excel.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(excel.content());
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }
}
