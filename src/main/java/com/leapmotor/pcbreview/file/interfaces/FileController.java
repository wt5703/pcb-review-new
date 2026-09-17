package com.leapmotor.pcbreview.file.interfaces;

import com.leapmotor.pcbreview.common.ApiResponse;
import com.leapmotor.pcbreview.common.TraceIdFilter;
import com.leapmotor.pcbreview.file.application.FileApplicationService;
import com.leapmotor.pcbreview.file.domain.FileCategory;
import com.leapmotor.pcbreview.identity.application.CurrentUserHolder;
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
public class FileController {
    private final FileApplicationService fileApplicationService;

    public FileController(FileApplicationService fileApplicationService) {
        this.fileApplicationService = fileApplicationService;
    }

    @PostMapping("/tasks/{taskId}/files/upload-sessions")
    ApiResponse<FileApplicationService.UploadSessionView> createUploadSession(@PathVariable long taskId,
                                                                                @Valid @RequestBody CreateUploadSessionRequest request,
                                                                                HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.createUploadSession(taskId, request.category(), CurrentUserHolder.require()),
                traceId(servletRequest));
    }

    @PostMapping("/tasks/{taskId}/files")
    ApiResponse<FileApplicationService.FileView> register(@PathVariable long taskId, @Valid @RequestBody RegisterFileRequest request,
                                                          HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.register(new FileApplicationService.RegisterFileCommand(taskId,
                request.uploadSessionId(), request.category(), request.businessFileKey(), request.fileName(), request.fileSize(), request.md5()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/files/{fileId}/download")
    ApiResponse<FileApplicationService.DownloadView> download(@PathVariable long fileId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.requestDownload(fileId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    record CreateUploadSessionRequest(@NotNull FileCategory category) {
    }

    record RegisterFileRequest(@NotBlank String uploadSessionId, @NotNull FileCategory category,
                               @NotBlank String businessFileKey, @NotBlank String fileName,
                               @Min(0) long fileSize, @NotBlank String md5) {
    }
}
