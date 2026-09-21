package com.bms.file.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.file.application.FileApplicationService;
import com.bms.file.domain.FileCategory;
import com.bms.file.domain.FileUploadScene;
import com.bms.identity.application.CurrentUserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 承载 PCB 任务文件的场景校验、元数据关联、查询和受控下载；资源服务 HTTP 调用由 FileController 及 ResourceServiceClient 负责。
 */
@RestController
@RequestMapping("/tasks/{taskId}/files")
@Tag(name = "任务文件", description = "登记已上传的任务阶段文件、查询当前文件并在任务权限校验后下载。")
public class TaskFileController {
    private final FileApplicationService fileApplicationService;

    public TaskFileController(FileApplicationService fileApplicationService) {
        this.fileApplicationService = fileApplicationService;
    }

    @PostMapping
    @Operation(summary = "登记任务阶段文件", description = "资源文件须先通过 POST /files/upload 上传至公司资源服务。该接口仅将 companyFileId 和元数据关联到任务，并按 category 场景校验。category 只允许 PROCESS_REVIEW、SCHEMATIC_REVIEW、MUTUAL_CHECK_REVIEW；不接收文件二进制或 businessFileKey。")
    ApiResponse<FileApplicationService.FileView> register(@PathVariable long taskId, @Valid @RequestBody StageFileRequest request,
                                                           HttpServletRequest servletRequest) {
        FileApplicationService.FileReferenceCommand reference = new FileApplicationService.FileReferenceCommand(
                request.companyFileId(), request.fileName(), request.fileSize(), request.md5(), null);
        return ApiResponse.ok(fileApplicationService.registerStageFile(taskId, request.category(), reference, request.fileKind(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/latest")
    @Operation(summary = "查询任务当前阶段文件", description = "按内部文件类别返回当前文件。工艺/结构图评审为 PROCESS，原理图评审为 PCB_SCHEMATIC，互检单评审为 MUTUAL_CHECK_ATTACHMENT。")
    ApiResponse<java.util.List<FileApplicationService.FileView>> latest(@PathVariable long taskId,
            @RequestParam @Schema(description = "内部文件类别：PCB_SCHEMATIC、PROCESS、STRUCTURE、MUTUAL_CHECK_ATTACHMENT", requiredMode = Schema.RequiredMode.REQUIRED) FileCategory category,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.listLatestByCategory(taskId, category, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/{fileId}/content")
    @Operation(summary = "下载任务文件", description = "先校验任务归属与当前用户文件权限，再由后端调用公司资源服务 POST /leapmotor/pm/resource/download 并代理返回二进制内容。")
    ResponseEntity<byte[]> downloadContent(@PathVariable long taskId, @PathVariable long fileId) {
        FileApplicationService.DownloadContent content = fileApplicationService.downloadTaskFile(taskId, fileId, CurrentUserHolder.require());
        return ResponseEntity.ok().header("Content-Disposition", ContentDisposition.attachment().filename(content.fileName()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(content.content());
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "已上传资源文件与任务阶段的关联请求")
    record StageFileRequest(
            @Schema(description = "上传场景：PROCESS_REVIEW、SCHEMATIC_REVIEW、MUTUAL_CHECK_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull FileUploadScene category,
            @Schema(description = "公司资源服务返回的文件标识或路径", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String companyFileId,
            @Schema(description = "原始文件名", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String fileName,
            @Schema(description = "文件大小（字节）", requiredMode = Schema.RequiredMode.REQUIRED) @PositiveOrZero long fileSize,
            @Schema(description = "工艺/结构评审文件的内部类型：PROCESS 或 STRUCTURE；不传时由场景生成默认内部关联键") String fileKind,
            @Schema(description = "MD5，可选") String md5) { }
}
