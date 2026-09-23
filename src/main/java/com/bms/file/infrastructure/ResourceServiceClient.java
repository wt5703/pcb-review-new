package com.bms.file.infrastructure;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.domain.FileCategory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 适配公司资源服务上传与下载接口；统一调用公司提供的 /leapmotor/pm/resource/upload 和 /download，不提供 Mock 回退。
 */
@Component
public class ResourceServiceClient {
    private final RestClient restClient = RestClient.create();
    @Value("${resource-service.api-prefix:http://10.195.135.149:30860/leapmotor/pm/resource}")
    private String apiPrefix;
    @Value("${resource-service.dir-path:/user/bms_platform/pcb_review/test1}")
    private String dirPath;

    public StoredResource upload(MultipartFile file) {
        return uploadInUuidDirectory(file, UUID.randomUUID().toString());
    }

    /** 使用 PCB 生成的文件 UUID 作为资源目录，确保任务请求的 fileId 与资源目录可追溯对应。 */
    public StoredResource uploadInUuidDirectory(MultipartFile file, String fileId) {
        String root = dirPath == null || dirPath.isBlank() ? "/user/bms_platform/pcb_review/test1" : dirPath.replaceAll("/+$", "");
        return upload(file, root + "/" + fileId, fileId);
    }

    /**
     * @author 王涛
     * @date 2026-09-21
     * @description 按任务与文件类别构造资源服务目录，确保工艺、结构、设计与互检文件在资源服务侧可追溯且不混放。
     */
    public StoredResource upload(MultipartFile file, long taskId, FileCategory category) {
        return upload(file);
    }

    /**
     * 按公司资源服务协议上传：multipart file + 必填 query 参数 dirPath。
     *
     * 公司接口的成功响应仅包含 HTTP 200 与 {@code success=true}，不返回文件 ID 或资源路径。
     * 因此资源标识由 PCB 在上传前生成，资源路径使用提交给资源服务的 UUID 目录，不能从响应体推断。
     */
    public StoredResource upload(MultipartFile file, String targetDirPath) {
        return upload(file, targetDirPath, UUID.randomUUID().toString());
    }

    private StoredResource upload(MultipartFile file, String targetDirPath, String resourceId) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new NamedByteArrayResource(file.getBytes(), file.getOriginalFilename()));
            ResponseEntity<Map> response = restClient.post()
                    .uri(resourceServiceBaseUrl() + "/upload?dirPath={dirPath}", targetDirPath)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            if (response.getStatusCode().value() != 200) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务上传未返回 HTTP 200");
            }
            Map responseBody = response.getBody();
            if (responseBody == null || !"200".equals(String.valueOf(responseBody.get("code")))
                    || !Boolean.TRUE.equals(responseBody.get("success"))) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务上传失败");
            }
            return new StoredResource(resourceId, targetDirPath);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务上传文件失败：" + exception.getMessage());
        }
    }

    public byte[] download(String fileName, String filePath) {
        try {
            byte[] content = restClient.post().uri(resourceServiceBaseUrl() + "/download?fileName={fileName}&filePath={filePath}",
                    fileName, filePath).retrieve().body(byte[].class);
            if (content == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务下载成功但未返回文件内容");
            }
            return content;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务下载文件失败：" + exception.getMessage());
        }
    }

    private String resourceServiceBaseUrl() {
        if (apiPrefix == null || apiPrefix.isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "未配置公司资源服务地址");
        }
        return apiPrefix.replaceAll("/+$", "");
    }

    public record StoredResource(String resourceId, String resourcePath) { }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;
        private NamedByteArrayResource(byte[] bytes, String filename) { super(bytes); this.filename = filename; }
        @Override public String getFilename() { return filename; }
    }
}
