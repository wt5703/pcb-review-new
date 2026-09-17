package com.leapmotor.pcbreview.file.infrastructure;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.file.domain.FileCategory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 本地开发阶段替代公司文件服务的上传会话适配器，生成一次性上传会话和模拟文件服务标识，不保存任何二进制文件内容。
 */
@Component
public class MockFileStorage {
    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();

    public UploadSession createSession(long taskId, long userId, FileCategory category) {
        String sessionId = UUID.randomUUID().toString();
        UploadSession session = new UploadSession(sessionId, taskId, userId, category, "mock-file-" + UUID.randomUUID());
        sessions.put(sessionId, session);
        return session;
    }

    public UploadSession consumeSession(String sessionId, long taskId, long userId, FileCategory category) {
        UploadSession session = sessions.remove(sessionId);
        if (session == null || session.taskId() != taskId || session.userId() != userId || session.category() != category) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "上传会话无效或不属于当前用户");
        }
        return session;
    }

    public record UploadSession(String sessionId, long taskId, long userId, FileCategory category, String companyFileId) {
        public String uploadUrl() {
            return "mock://company-file/upload/" + sessionId;
        }
    }
}
