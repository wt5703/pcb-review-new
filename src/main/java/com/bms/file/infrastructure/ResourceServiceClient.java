package com.bms.file.infrastructure;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.domain.FileCategory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
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
 * @description 适配公司资源服务上传与下载接口；生产环境调用 /leapmotor/pm/resource/upload 和 /download，本地未配置地址时返回可测试的 Mock 资源路径。
 */
@Component
public class ResourceServiceClient {
    private final RestClient restClient = RestClient.create();
    @Value("${resource-service.base-url:}")
    private String baseUrl;
    @Value("${resource-service.dir-path:/bms/pcb}")
    private String dirPath;

    public StoredResource upload(MultipartFile file) {
        return upload(file, dirPath);
    }

    /**
     * @author 王涛
     * @date 2026-09-21
     * @description 按任务与文件类别构造资源服务目录，确保工艺、结构、设计与互检文件在资源服务侧可追溯且不混放。
     */
    public StoredResource upload(MultipartFile file, long taskId, FileCategory category) {
        String root = dirPath == null || dirPath.isBlank() ? "/bms/pcb" : dirPath.replaceAll("/+$", "");
        return upload(file, root + "/tasks/" + taskId + "/" + category.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** 按公司资源服务协议上传：multipart file + 必填 query 参数 dirPath。 */
    public StoredResource upload(MultipartFile file, String targetDirPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new StoredResource("mock-resource-" + UUID.randomUUID(), targetDirPath + "/" + file.getOriginalFilename());
        }
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new NamedByteArrayResource(file.getBytes(), file.getOriginalFilename()));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post().uri(baseUrl + "/leapmotor/pm/resource/upload?dirPath={dirPath}", targetDirPath)
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(Map.class);
            String path = value(response, "filePath", "path", "resourcePath", "id");
            return new StoredResource(path == null ? "resource-" + UUID.randomUUID() : path,
                    path == null ? targetDirPath + "/" + file.getOriginalFilename() : path);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务上传文件失败：" + exception.getMessage());
        }
    }

    public byte[] download(String fileName, String filePath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new byte[0];
        }
        try {
            byte[] content = restClient.post().uri(baseUrl + "/leapmotor/pm/resource/download?fileName={fileName}&filePath={filePath}",
                    fileName, filePath).retrieve().body(byte[].class);
            return content == null ? new byte[0] : content;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "资源服务下载文件失败：" + exception.getMessage());
        }
    }

    private String value(Map<String, Object> response, String... keys) {
        if (response == null) { return null; }
        for (String key : keys) {
            Object result = response.get(key);
            if (result != null) { return String.valueOf(result); }
        }
        Object data = response.get("data");
        if (data instanceof CharSequence value) {
            return value.toString();
        }
        return data instanceof Map<?, ?> nested ? value((Map<String, Object>) nested, keys) : null;
    }

    public record StoredResource(String resourceId, String resourcePath) { }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;
        private NamedByteArrayResource(byte[] bytes, String filename) { super(bytes); this.filename = filename; }
        @Override public String getFilename() { return filename; }
    }
}
