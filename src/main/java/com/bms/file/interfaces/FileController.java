package com.bms.file.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.file.application.FileApplicationService;
import com.bms.file.domain.FileCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 调用公司现有api实现文件的上传与下载。
 */
@RestController
@Validated
@Tag(name = "文件", description = "文件的上传与下载")
public class FileController {
    private final FileApplicationService fileApplicationService;

    public FileController(FileApplicationService fileApplicationService) {
        this.fileApplicationService = fileApplicationService;
    }

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文件", description = "上传文件统一调用该接口 创建任务的时候上传文件，此时taskId可不传")
    ApiResponse<UploadFileView> upload(@RequestPart("file") MultipartFile multipartFile,
                                       @RequestParam(required = false) Long taskId,
                                       @RequestParam @NotNull @Schema(description = "PCB评审=PCB_REVIEW，PCB工艺评审=PCB_PROCESS_REVIEW，PCB结构评审=PCB_STRUCTURE_REVIEW，原理图评审=SCHEMATIC_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED) FileCategory fileCategory,
                                       @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        if (taskId == null) {
            FileApplicationService.UploadedFileView uploaded = fileApplicationService.uploadPendingInitialFile(multipartFile, fileCategory,
                    employeeNo, null);
            return ApiResponse.ok(new UploadFileView(uploaded.fileId(), uploaded.taskFileId(), uploaded.fileName(), uploaded.fileSize(), uploaded.fileCategory()), traceId(servletRequest));
        }
        FileApplicationService.FileView storedFile = fileApplicationService.upload(taskId, fileCategory, multipartFile, employeeNo, null);
        return ApiResponse.ok(new UploadFileView(storedFile.fileId(), storedFile.id(), storedFile.fileName(), storedFile.fileSize(), storedFile.category()), traceId(servletRequest));
    }

    @PostMapping("/files/download")
    @Operation(summary = "下载文件", description = "只传 review_file 主键 fileId。后端从文件记录取得任务归属和文件类别，完成权限校验后代理公司资源服务下载。")
    ResponseEntity<byte[]> downloadContent(@RequestParam long fileId,
                                           @NotBlank @RequestHeader("userId") String employeeNo) {
        FileApplicationService.DownloadContent content = fileApplicationService.downloadFile(fileId, employeeNo, null);
        return ResponseEntity.ok().header("Content-Disposition", ContentDisposition.attachment().filename(content.fileName()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(content.content());
    }

    @DeleteMapping("/files")
    @Operation(summary = "取消上传文件", description = "fileId 为上传接口返回的文件 UUID，不是 review_file 主键。仅解除该文件与 taskId 的绑定关系，不删除公司资源服务中的原始文件。")
    ApiResponse<Void> unbind(@RequestParam long taskId,
                             @RequestParam @NotBlank @Schema(description = "文件 UUID，即上传接口返回的 fileId", requiredMode = Schema.RequiredMode.REQUIRED) String fileId,
                             @NotBlank @RequestHeader("userId") String employeeNo,
                             HttpServletRequest servletRequest) {
        fileApplicationService.unbindFile(taskId, fileId, employeeNo, null);
        return ApiResponse.ok(null, traceId(servletRequest));
    }

    @GetMapping("/files/latest")
    @Operation(summary = "查询任务当前节点的最新文件", description = "按任务和文件类别查询当前有效文件；类别仅支持四类 FileCategory。")
    ApiResponse<java.util.List<FileApplicationService.FileView>> latest(@RequestParam long taskId,
                                                                        @RequestParam FileCategory fileCategory, @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.listLatestByCategory(taskId, fileCategory, employeeNo, null), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }

    record UploadFileView(String fileId, Long taskFileId, String fileName, long fileSize, FileCategory fileCategory) { }
}
