package com.bms.file.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.file.application.FileApplicationService;
import com.bms.file.domain.FileCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bms.identity.application.CurrentUserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 对外提供本地上传会话创建、文件元数据登记和受控下载地址获取接口，不直接接收或转发文件二进制内容。
 */
@RestController
@Tag(name = "评审文件", description = "创建文件上传会话、登记文件元数据并申请受控下载地址。当前本地 Mock 不保存二进制文件。")
public class FileController {
    private final FileApplicationService fileApplicationService;

    public FileController(FileApplicationService fileApplicationService) {
        this.fileApplicationService = fileApplicationService;
    }

    @PostMapping("/tasks/{taskId}/files/upload-sessions")
    @Operation(summary = "创建文件上传会话", description = "按任务和文件类别创建一次上传会话，返回本地 Mock 上传地址。")
    ApiResponse<FileApplicationService.UploadSessionView> createUploadSession(@PathVariable long taskId,
                                                                                @Valid @RequestBody CreateUploadSessionRequest request,
                                                                                HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.createUploadSession(taskId, request.category(), CurrentUserHolder.require()),
                traceId(servletRequest));
    }

    @PostMapping("/tasks/{taskId}/files")
    @Operation(summary = "登记评审文件元数据", description = "登记已完成上传的文件名称、大小、MD5、业务文件键和类别；同一业务文件键会形成版本链。")
    ApiResponse<FileApplicationService.FileView> register(@PathVariable long taskId, @Valid @RequestBody RegisterFileRequest request,
                                                          HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.register(new FileApplicationService.RegisterFileCommand(taskId,
                request.uploadSessionId(), request.category(), request.businessFileKey(), request.fileName(), request.fileSize(), request.md5()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/files/{fileId}/download")
    @Operation(summary = "申请文件下载地址", description = "根据当前用户的数据与文件权限返回受控下载地址；EMC 专家具备 PCB/原理图文件下载例外。")
    ApiResponse<FileApplicationService.DownloadView> download(@PathVariable long fileId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.requestDownload(fileId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "创建文件上传会话请求")
    record CreateUploadSessionRequest(@Schema(description = "文件类别，例如 PCB_SCHEMATIC", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull FileCategory category) {
    }

    @Schema(description = "登记评审文件元数据请求")
    record RegisterFileRequest(@Schema(description = "上传会话 ID", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String uploadSessionId,
                               @Schema(description = "文件类别", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull FileCategory category,
                               @Schema(description = "业务文件键；相同键的文件生成版本链", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String businessFileKey,
                               @Schema(description = "原始文件名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String fileName,
                               @Schema(description = "文件大小，单位字节", requiredMode = Schema.RequiredMode.REQUIRED) @Min(0) long fileSize,
                               @Schema(description = "文件 MD5 摘要", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String md5) {
    }
}
