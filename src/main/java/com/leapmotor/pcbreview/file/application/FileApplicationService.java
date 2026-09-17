package com.leapmotor.pcbreview.file.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.file.domain.FileCategory;
import com.leapmotor.pcbreview.file.domain.FileVersionPolicy;
import com.leapmotor.pcbreview.file.domain.UploadDecision;
import com.leapmotor.pcbreview.file.infrastructure.MockFileStorage;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileMapper;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileRecord;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 编排本地文件上传会话、文件元数据登记、MD5 版本去重和下载授权，平台只保存元数据并以 Mock 地址替代公司文件服务调用。
 */
@Service
public class FileApplicationService {
    private final ReviewFileMapper fileMapper;
    private final ReviewTaskMapper taskMapper;
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper;
    private final MockFileStorage storage;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();
    private final FileVersionPolicy fileVersionPolicy = new FileVersionPolicy();

    public FileApplicationService(ReviewFileMapper fileMapper, ReviewTaskMapper taskMapper,
                                  TaskAssignmentAccessMapper taskAssignmentAccessMapper, MockFileStorage storage) {
        this.fileMapper = fileMapper;
        this.taskMapper = taskMapper;
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
        this.storage = storage;
    }

    public UploadSessionView createUploadSession(long taskId, FileCategory category, CurrentUser currentUser) {
        requireTaskAccess(taskId, currentUser, category, true);
        MockFileStorage.UploadSession session = storage.createSession(taskId, currentUser.id(), category);
        return new UploadSessionView(session.sessionId(), session.uploadUrl());
    }

    @Transactional
    public FileView register(RegisterFileCommand command, CurrentUser currentUser) {
        requireTaskAccess(command.taskId(), currentUser, command.category(), true);
        MockFileStorage.UploadSession session = storage.consumeSession(command.uploadSessionId(), command.taskId(), currentUser.id(), command.category());
        ReviewFileRecord latest = fileMapper.findLatest(command.taskId(), command.category().name(), command.businessFileKey());
        if (latest != null) {
            UploadDecision decision = fileVersionPolicy.decide(latest.getMd5(), command.md5(), latest.getVersionNo());
            if (decision.duplicate()) {
                return FileView.from(latest);
            }
            fileMapper.markLatestAsHistorical(command.taskId(), command.category().name(), command.businessFileKey());
            ReviewFileRecord next = toRecord(nextId(), command, currentUser.id(), session.companyFileId(), decision.versionNo());
            fileMapper.insert(next);
            return FileView.from(next);
        }
        ReviewFileRecord first = toRecord(nextId(), command, currentUser.id(), session.companyFileId(), 1);
        fileMapper.insert(first);
        return FileView.from(first);
    }

    public DownloadView requestDownload(long fileId, CurrentUser currentUser) {
        ReviewFileRecord file = fileMapper.findById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        FileCategory category = FileCategory.valueOf(file.getFileCategory());
        requireTaskAccess(file.getTaskId(), currentUser, category, false);
        return new DownloadView(file.getId(), "mock://company-file/download/" + file.getCompanyFileId());
    }

    private void requireTaskAccess(long taskId, CurrentUser currentUser, FileCategory category, boolean upload) {
        if (!permissionPolicy.has(currentUser.roles(), upload ? category.uploadPermission() : category.downloadPermission())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无对应文件操作权限");
        }
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        if (!permissionPolicy.canViewAllTasks(currentUser.roles())
                && !taskAssignmentAccessMapper.isAssignedToTask(taskId, currentUser.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该任务的文件");
        }
    }

    private synchronized long nextId() {
        return fileMapper.nextId();
    }

    private ReviewFileRecord toRecord(long id, RegisterFileCommand command, long uploadedBy, String companyFileId, int versionNo) {
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(id);
        record.setTaskId(command.taskId());
        record.setFileCategory(command.category().name());
        record.setBusinessFileKey(command.businessFileKey());
        record.setFileName(command.fileName());
        record.setFileSize(command.fileSize());
        record.setMd5(command.md5());
        record.setVersionNo(versionNo);
        record.setCompanyFileId(companyFileId);
        record.setLatest(true);
        record.setUploadedBy(uploadedBy);
        return record;
    }

    public record RegisterFileCommand(long taskId, String uploadSessionId, FileCategory category, String businessFileKey,
                                      String fileName, long fileSize, String md5) {
        public RegisterFileCommand {
            if (uploadSessionId == null || uploadSessionId.isBlank() || businessFileKey == null || businessFileKey.isBlank()
                    || fileName == null || fileName.isBlank() || md5 == null || md5.isBlank() || fileSize < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件登记参数不合法");
            }
        }
    }

    public record UploadSessionView(String uploadSessionId, String uploadUrl) {
    }

    public record FileView(Long id, FileCategory category, String businessFileKey, String fileName, Long fileSize,
                           String md5, Integer versionNo, boolean latest) {
        static FileView from(ReviewFileRecord record) {
            return new FileView(record.getId(), FileCategory.valueOf(record.getFileCategory()), record.getBusinessFileKey(),
                    record.getFileName(), record.getFileSize(), record.getMd5(), record.getVersionNo(), record.getLatest());
        }
    }

    public record DownloadView(Long fileId, String downloadUrl) {
    }
}
