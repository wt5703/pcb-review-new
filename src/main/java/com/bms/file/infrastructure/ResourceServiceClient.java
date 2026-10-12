package com.bms.file.infrastructure;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 适配公司资源服务上传与下载接口；本地开发可通过配置返回内存 Mock 结果，生产环境统一调用公司接口。
 */
@Component
public class ResourceServiceClient {
    private final RestClient restClient = RestClient.create();
    @Value("${resource-service.api-prefix}")
    private String apiPrefix;
    @Value("${resource-service.upload-path}")
    private String uploadPath;
    @Value("${resource-service.download-path}")
    private String downloadPath;
    @Value("${resource-service.dir-path}")
    private String dirPath;
    @Value("${resource-service.mock-enabled}")
    private boolean mockEnabled;
    private final Map<String, byte[]> mockResources = new ConcurrentHashMap<>();

    /** 使用 PCB 生成的 UUID 作为资源目录与文件唯一标识。 */
    public StoredResource upload(MultipartFile file, String fileId) {
        String root = requiredConfig(dirPath, "资源服务目录").replaceAll("/+$", "");
        String targetDirPath = root + "/" + fileId;
        try {
            if (mockEnabled) {
                mockResources.put(targetDirPath, file.getBytes());
                return new StoredResource(fileId, targetDirPath);
            }
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new NamedByteArrayResource(file.getBytes(), file.getOriginalFilename()));
            ResponseEntity<Map> response = restClient.post()
                    .uri(resourceServiceUrl(uploadPath, "上传") + "?dirPath={dirPath}", targetDirPath)
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
            return new StoredResource(fileId, targetDirPath);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务上传文件失败：" + exception.getMessage());
        }
    }

    public byte[] download(String fileName, String filePath) {
        if (mockEnabled) {
            return mockResources.getOrDefault(filePath,
                    ("Mock 文件内容：" + fileName).getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] content = restClient.post().uri(resourceServiceUrl(downloadPath, "下载") + "?fileName={fileName}&filePath={filePath}",
                    fileName, filePath).retrieve().body(byte[].class);
            if (content == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务下载成功但未返回文件内容");
            }
            return content;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务下载文件失败：" + exception.getMessage());
        }
    }

    private String resourceServiceUrl(String operationPath, String operationName) {
        String baseUrl = requiredConfig(apiPrefix, "公司资源服务地址").replaceAll("/+$", "");
        String path = requiredConfig(operationPath, "资源服务" + operationName + "接口路径").trim();
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private String requiredConfig(String value, String configName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "未配置" + configName);
        }
        return value;
    }

    public record StoredResource(String resourceId, String resourcePath) { }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;
        private NamedByteArrayResource(byte[] bytes, String filename) { super(bytes); this.filename = filename; }
        @Override public String getFilename() { return filename; }
    }
}
