package com.bms.file.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.file.infrastructure.ResourceServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 仅代理公司资源服务的通用上传和下载协议；不包含任务、评审场景、文件元数据或权限等 PCB 业务逻辑。
 */
@RestController
@Tag(name = "资源文件", description = "公司资源服务的上传和下载代理。任务文件关联、权限和流程场景由任务文件接口处理。")
public class FileController {
    private final ResourceServiceClient resourceServiceClient;

    public FileController(ResourceServiceClient resourceServiceClient) {
        this.resourceServiceClient = resourceServiceClient;
    }

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传资源文件", description = "严格代理公司 POST /leapmotor/pm/resource/upload：只接收 file 和必填 dirPath，并原样返回资源服务标识与路径。不处理任务、评审场景或文件元数据。")
    ApiResponse<ResourceServiceClient.StoredResource> upload(@RequestPart("file") MultipartFile file,
                                                               @RequestParam @NotBlank String dirPath,
                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(resourceServiceClient.upload(file, dirPath), traceId(servletRequest));
    }

    @PostMapping("/files/download")
    @Operation(summary = "下载资源文件", description = "严格代理公司 POST /leapmotor/pm/resource/download：只接收必填 fileName 和 filePath，并返回资源文件二进制内容。不处理任务权限或 PCB 文件元数据。")
    ResponseEntity<byte[]> downloadContent(@RequestParam @NotBlank String fileName, @RequestParam @NotBlank String filePath) {
        byte[] content = resourceServiceClient.download(fileName, filePath);
        return ResponseEntity.ok().header("Content-Disposition", ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(content);
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

}
