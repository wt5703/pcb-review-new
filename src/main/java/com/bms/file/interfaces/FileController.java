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
import jakarta.validation.constraints.NotEmpty;
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

import java.util.List;

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
            FileApplicationService.UploadedFileView uploaded = fileApplicationService.uploadPendingInitialFile(multipartFile, fileCategory, employeeNo);
            return ApiResponse.ok(new UploadFileView(uploaded.fileId(), uploaded.taskFileId(), uploaded.fileName(), uploaded.fileSize(), uploaded.fileCategory()), traceId(servletRequest));
        }
        FileApplicationService.FileView storedFile = fileApplicationService.upload(taskId, fileCategory, multipartFile, employeeNo);
        return ApiResponse.ok(new UploadFileView(storedFile.fileId(), storedFile.id(), storedFile.fileName(), storedFile.fileSize(), storedFile.category()), traceId(servletRequest));
    }

    @PostMapping(value = "/files/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "批量上传任务文件", description = "传 taskId、fileCategory 及多个同名 files 文件字段。整批上传成功后绑定任务，同类型原有最新文件转为历史，本批文件均为最新。sendMail 默认 false；工艺/结构评审开启后传 true，在全部文件落库成功后向该类型的评审人员发送开启评审邮件，附件为该类型最新批次文件。")
    ApiResponse<List<UploadFileView>> uploadBatch(@RequestParam long taskId,
            @RequestPart("files") @NotEmpty List<MultipartFile> files, @RequestParam FileCategory fileCategory,
            @RequestParam(defaultValue = "false") @Schema(description = "是否发送工艺/结构评审邮件，默认 false；仅工艺/结构评审阶段的对应文件可传 true") boolean sendMail,
            @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        List<UploadFileView> uploaded = fileApplicationService.uploadBatch(taskId, fileCategory, files, employeeNo, sendMail).stream()
                .map(file -> new UploadFileView(file.fileId(), file.id(), file.fileName(), file.fileSize(), file.category())).toList();
        return ApiResponse.ok(uploaded, traceId(servletRequest));
    }

    @PostMapping(value = "/files/upload/grouped", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "按多个文件类型批量上传", description = "传 taskId 和多个 fileCategories 参数。文件 part 名称使用对应 FileCategory 编码，每类可重复多个文件，例如 fileCategories=PCB_REVIEW&fileCategories=PCB_STRUCTURE_REVIEW，文件字段 PCB_REVIEW=a.zip、PCB_REVIEW=b.pdf、PCB_STRUCTURE_REVIEW=c.zip。每类至少一个文件，响应按类型分组。同类旧文件转历史，本批全部为最新。")
    ApiResponse<List<UploadFileGroupView>> uploadGrouped(@RequestParam long taskId,
            @RequestParam @NotEmpty List<FileCategory> fileCategories,
            org.springframework.web.multipart.MultipartHttpServletRequest request,
            @NotBlank @RequestHeader("userId") String employeeNo) {
        java.util.Map<FileCategory, List<MultipartFile>> groups = new java.util.LinkedHashMap<>();
        for (FileCategory category : fileCategories) {
            if (groups.putIfAbsent(category, request.getFiles(category.name())) != null) {
                throw new com.bms.common.BusinessException(com.bms.common.ErrorCode.VALIDATION_ERROR, "文件类型不能重复");
            }
        }
        if (!request.getMultiFileMap().keySet().equals(groups.keySet().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()))) {
            throw new com.bms.common.BusinessException(com.bms.common.ErrorCode.VALIDATION_ERROR, "文件字段名必须与 fileCategories 对应且每类均有文件");
        }
        var result = fileApplicationService.uploadGrouped(taskId, groups, employeeNo).stream()
                .map(group -> new UploadFileGroupView(group.fileCategory(), group.files().stream()
                        .map(file -> new UploadFileView(file.fileId(), file.id(), file.fileName(), file.fileSize(), file.category())).toList())).toList();
        return ApiResponse.ok(result, traceId(request));
    }

    @Schema(description = "按文件类型返回的上传结果")
    record UploadFileGroupView(@Schema(description = "文件业务类型") FileCategory fileCategory,
                               @Schema(description = "该类型本次上传的所有文件") List<UploadFileView> files) { }

    @PostMapping("/files/download")
    @Operation(summary = "下载文件", description = "传 review_file 主键 fileId。所有登录用户均可下载任务文件；后端确认文件及所属任务存在后代理资源服务下载。上传、取消上传仍限制为任务设计者。")
    ResponseEntity<byte[]> downloadContent(@RequestParam long fileId,
                                           @NotBlank @RequestHeader("userId") String employeeNo) {
        FileApplicationService.DownloadContent content = fileApplicationService.downloadFile(fileId, employeeNo);
        return ResponseEntity.ok().header("Content-Disposition", ContentDisposition.attachment().filename(content.fileName()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(content.content());
    }

    @DeleteMapping("/files")
    @Operation(summary = "取消上传文件", description = "fileId 为上传接口返回的文件 UUID，不是 review_file 主键。仅解除该文件与 taskId 的绑定关系，不删除公司资源服务中的原始文件。")
    ApiResponse<Void> unbind(@RequestParam long taskId,
                             @RequestParam @NotBlank @Schema(description = "文件 UUID，即上传接口返回的 fileId", requiredMode = Schema.RequiredMode.REQUIRED) String fileId,
                             @NotBlank @RequestHeader("userId") String employeeNo,
                             HttpServletRequest servletRequest) {
        fileApplicationService.unbindFile(taskId, fileId, employeeNo);
        return ApiResponse.ok(null, traceId(servletRequest));
    }

    @GetMapping("/files/latest")
    @Operation(summary = "查询任务当前节点的最新文件", description = "按任务和文件类别查询当前有效文件；类别仅支持四类 FileCategory。")
    ApiResponse<java.util.List<FileApplicationService.FileView>> latest(@RequestParam long taskId,
                                                                        @RequestParam FileCategory fileCategory, @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(fileApplicationService.listLatestByCategory(taskId, fileCategory, employeeNo), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }

    record UploadFileView(String fileId, Long taskFileId, String fileName, long fileSize, FileCategory fileCategory) { }
}
