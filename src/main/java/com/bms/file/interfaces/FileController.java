package com.bms.file.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.file.application.FileApplicationService;
import com.bms.file.domain.FileCategory;
import com.bms.identity.application.CurrentUserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 统一处理公司资源服务代理、任务文件元数据登记、当前文件查询及授权下载。
 */
@RestController
@Tag(name = "文件", description = "文件的上传与下载")
public class FileController {
    private final FileApplicationService fileApplicationService;

    public FileController(FileApplicationService fileApplicationService) {
        this.fileApplicationService = fileApplicationService;
    }

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文件", description = "前端仅传 file、可选 taskId 和必填 fileCategory。后端从环境配置读取基础目录并追加 UUID，调用公司资源服务 /upload。没有 taskId 时仅允许 TASK_CREATION，后端保存临时文件元数据并返回 UUID；创建或保存任务时将多个 UUID 放入 files。")
    ApiResponse<UploadFileView> upload(@RequestPart("file") MultipartFile multipartFile,
                                       @RequestParam(required = false) Long taskId,
                                       @RequestParam @NotNull @Schema(description = "任务创建=TASK_CREATION PCB评审=PCB_REVIEW 原理图评审=SCHEMATIC_REVIEW 互检单评审=MUTUAL_CHECK_REVIEW 工艺评审=PROCESS_REVIEW 结构评审=STRUCTURE_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED) FileCategory fileCategory,
                                       HttpServletRequest servletRequest) {
        if (taskId == null) {
            FileApplicationService.PendingUploadView uploaded = fileApplicationService.uploadPendingInitialFile(multipartFile, fileCategory,
                    CurrentUserHolder.require());
            return ApiResponse.ok(new UploadFileView(uploaded.fileId(), null, uploaded.fileName(), uploaded.fileSize(), uploaded.fileCategory()), traceId(servletRequest));
        }
        FileApplicationService.FileView storedFile = fileApplicationService.uploadAndRegister(taskId, fileCategory, multipartFile, CurrentUserHolder.require());
        return ApiResponse.ok(new UploadFileView(String.valueOf(storedFile.id()), storedFile.id(), storedFile.fileName(), storedFile.fileSize(), storedFile.category()), traceId(servletRequest));
    }

    @GetMapping("/files/latest")
    @Operation(summary = "查询任务当前节点的最新文件", description = "按任务和文件类别查询当前文件；类别必须为六类 FileCategory 之一。")
    ApiResponse<java.util.List<FileApplicationService.FileView>> latest(@RequestParam long taskId,
            @RequestParam FileCategory fileCategory, HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.listLatestByCategory(taskId, fileCategory, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/files/download")
    @Operation(summary = "下载文件", description = "只传 taskId 和唯一 fileId。后端读取已登记文件的类别并完成权限校验，再代理公司资源服务下载。")
    ResponseEntity<byte[]> downloadContent(@RequestParam long taskId, @RequestParam long fileId) {
        FileApplicationService.DownloadContent content = fileApplicationService.downloadTaskFile(taskId, fileId, CurrentUserHolder.require());
        return ResponseEntity.ok().header("Content-Disposition", ContentDisposition.attachment().filename(content.fileName()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(content.content());
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }

    record UploadFileView(String fileId, Long taskFileId, String fileName, long fileSize, FileCategory fileCategory) { }
}
