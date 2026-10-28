package com.bms.archive.interfaces;

import com.bms.archive.application.TaskArchiveApplicationService;
import com.bms.archive.application.TaskArchiveOpinionExportApplicationService;
import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
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
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 提供已结束任务归档快照的只读查询接口，不允许通过任务标识绕过授权查看历史详情
 */
@RestController
@Validated
@RequestMapping("/tasks/{taskId}/archive")
@Tag(name = "任务归档", description = "读取已结束评审任务的不可变归档快照，返回流程节点、阶段文件和邮件投递记录。")
public class TaskArchiveController {
    private static final MediaType EXCEL_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
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
    ApiResponse<TaskArchiveApplicationService.ArchiveView> get(@PathVariable long taskId, @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        taskApplicationService.getTaskDetail(taskId, employeeNo, null);
        return ApiResponse.ok(taskArchiveApplicationService.get(taskId), traceId(servletRequest));
    }

    @GetMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(summary = "导出归档评审意见 Excel", description = "仅支持已结束任务。返回标准 .xlsx 二进制文件；PCB 按评审角色拆分工作表，原理图使用“评审问题记录”单工作表模板。一条意见存在多轮设计者答复时，每轮答复导出一行；不包含互检单意见。")
    ResponseEntity<byte[]> exportOpinions(@PathVariable long taskId) {
        // 导出服务会校验任务存在且已结束，并直接生成 xlsx 字节；不要额外执行详情或归档查询，
        // 否则其异常响应会被下载端当作文件保存，最终表现为乱码文本而不是 Excel。
        TaskArchiveOpinionExportApplicationService.ExportedExcel excel = taskArchiveOpinionExportApplicationService.export(taskId);
        return ResponseEntity.ok()
                .contentType(EXCEL_MEDIA_TYPE)
                .contentLength(excel.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentFileName(excel.fileName()))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .header("X-Content-Type-Options", "nosniff")
                .body(excel.content());
    }

    private String attachmentFileName(String fileName) {
        return ContentDisposition.attachment().filename(fileName).build().toString();
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }
}
