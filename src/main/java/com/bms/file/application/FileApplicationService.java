package com.bms.file.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.file.domain.FileCategory;
import com.bms.file.domain.FileUploadScene;
import com.bms.file.infrastructure.MockFileStorage;
import com.bms.file.infrastructure.ResourceServiceClient;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.notification.application.OutboxEventPublisher;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.task.domain.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 编排任务文件上传、文件元数据登记和下载授权；同一业务文件再次上传时替换当前文件，不维护文件版本号。
 */
@Service
public class FileApplicationService {
    private final ReviewFileMapper fileMapper;
    private final ReviewTaskMapper taskMapper;
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper;
    private final MockFileStorage storage;
    private final ResourceServiceClient resourceServiceClient;
    private final OutboxEventPublisher outboxEventPublisher;
    private final OperationAuditMapper auditMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    @Autowired
    public FileApplicationService(ReviewFileMapper fileMapper, ReviewTaskMapper taskMapper,
                                  TaskAssignmentAccessMapper taskAssignmentAccessMapper, MockFileStorage storage,
                                  ResourceServiceClient resourceServiceClient, OutboxEventPublisher outboxEventPublisher, OperationAuditMapper auditMapper) {
        this.fileMapper = fileMapper;
        this.taskMapper = taskMapper;
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
        this.storage = storage;
        this.resourceServiceClient = resourceServiceClient;
        this.outboxEventPublisher = outboxEventPublisher;
        this.auditMapper = auditMapper;
    }

    public FileApplicationService(ReviewFileMapper fileMapper, ReviewTaskMapper taskMapper,
                                  TaskAssignmentAccessMapper taskAssignmentAccessMapper, MockFileStorage storage,
                                  OutboxEventPublisher outboxEventPublisher, OperationAuditMapper auditMapper) {
        this(fileMapper, taskMapper, taskAssignmentAccessMapper, storage, null, outboxEventPublisher, auditMapper);
    }

    public UploadSessionView createUploadSession(long taskId, FileCategory category, CurrentUser currentUser) {
        requireTaskAccess(taskId, currentUser, category, true);
        MockFileStorage.UploadSession session = storage.createSession(taskId, currentUser.id(), category);
        return new UploadSessionView(session.sessionId(), session.uploadUrl());
    }

    @Transactional
    public FileView register(RegisterFileCommand command, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTaskAccess(command.taskId(), currentUser, command.category(), true);
        MockFileStorage.UploadSession session = storage.consumeSession(command.uploadSessionId(), command.taskId(), currentUser.id(), command.category());
        ReviewFileRecord latest = fileMapper.findLatest(command.taskId(), command.category().name(), command.businessFileKey());
        ReviewFileRecord record = toRecord(latest == null ? nextId() : latest.getId(), command, currentUser.id(), session.companyFileId(), task.getStatus());
        persist(record, latest);
        appendAudit(record, "FILE_REGISTERED", currentUser.id());
        outboxEventPublisher.publishTaskEvent("FILE_REGISTERED", command.taskId(), currentUser.id());
        return FileView.from(record);
    }

    /**
     * @author 王涛
     * @date 2026-09-21
     * @description 登记已由前端上传至公司资源服务的初始评审文件。PCB 系统不再接收文件二进制，只保存资源服务返回的唯一标识及文件元数据。
     */
    @Transactional
    public List<FileView> registerInitialFileReferences(long taskId, List<FileReferenceCommand> files, CurrentUser currentUser) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        requireTaskAccess(taskId, currentUser, FileCategory.PCB_SCHEMATIC, true);
        List<FileView> result = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            FileReferenceCommand file = files.get(index);
            if (file == null) { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评审文件不能为空"); }
            String key = file.businessFileKey() == null || file.businessFileKey().isBlank()
                    ? "INITIAL_" + String.format("%03d", index + 1) : file.businessFileKey().trim();
            ReviewFileRecord latest = fileMapper.findLatest(taskId, FileCategory.PCB_SCHEMATIC.name(), key);
            ReviewFileRecord record = referenceRecord(latest == null ? nextId() : latest.getId(), taskId, FileCategory.PCB_SCHEMATIC,
                    key, file, currentUser.id(), TaskStatus.DRAFT.name());
            persist(record, latest);
            appendAudit(record, "INITIAL_FILE_REGISTERED", currentUser.id());
            result.add(FileView.from(record));
        }
        return List.copyOf(result);
    }

    @Transactional
    public FileView uploadMultipartFile(long taskId, FileCategory category, String businessFileKey, MultipartFile file, CurrentUser currentUser) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "上传文件不能为空");
        }
        requireBusinessFileKey(businessFileKey);
        ReviewTaskRecord task = requireTaskAccess(taskId, currentUser, category, true);
        String md5 = md5(file);
        ReviewFileRecord latest = fileMapper.findLatest(taskId, category.name(), businessFileKey);
        ReviewFileRecord record = directRecord(latest == null ? nextId() : latest.getId(), taskId, category, businessFileKey, file, md5, currentUser.id(), task.getStatus());
        persist(record, latest);
        appendAudit(record, "FILE_UPLOADED", currentUser.id());
        outboxEventPublisher.publishTaskEvent("FILE_UPLOADED", taskId, currentUser.id());
        return FileView.from(record);
    }

    @Transactional
    public FileView registerStageFile(long taskId, FileUploadScene scene, FileReferenceCommand file, CurrentUser currentUser) {
        return registerStageFile(taskId, scene, file, null, currentUser);
    }

    @Transactional
    public FileView registerStageFile(long taskId, FileUploadScene scene, FileReferenceCommand file, String fileKind, CurrentUser currentUser) {
        FileCategory category = scene.fileCategory();
        if (!scene.requiresTask()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "创建任务文件应通过任务保存或提交接口关联");
        }
        ReviewTaskRecord task = requireTaskAccess(taskId, currentUser, category, true);
        String internalFileKey = stageFileKey(scene, fileKind);
        ReviewFileRecord latest = fileMapper.findLatest(taskId, category.name(), internalFileKey);
        ReviewFileRecord record = referenceRecord(latest == null ? nextId() : latest.getId(), taskId, category,
                internalFileKey, file, currentUser.id(), task.getStatus());
        persist(record, latest);
        appendAudit(record, "STAGE_FILE_REGISTERED", currentUser.id());
        outboxEventPublisher.publishTaskEvent("STAGE_FILE_REGISTERED", taskId, currentUser.id());
        return FileView.from(record);
    }

    private String stageFileKey(FileUploadScene scene, String fileKind) {
        if (fileKind == null || fileKind.isBlank()) return scene.name();
        String normalizedKind = fileKind.trim().toUpperCase(java.util.Locale.ROOT);
        if (scene != FileUploadScene.PROCESS_REVIEW || !("PROCESS".equals(normalizedKind) || "STRUCTURE".equals(normalizedKind))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前上传场景不支持该文件类型");
        }
        return scene.name() + "_" + normalizedKind;
    }

    public DownloadContent downloadTaskFile(long taskId, long fileId, CurrentUser currentUser) {
        ReviewFileRecord file = fileMapper.findById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        if (!Long.valueOf(taskId).equals(file.getTaskId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务文件不存在");
        }
        requireTaskAccess(file.getTaskId(), currentUser, FileCategory.valueOf(file.getFileCategory()), false);
        byte[] content = resourceServiceClient == null ? new byte[0] : resourceServiceClient.download(file.getFileName(), file.getCompanyFileId());
        return new DownloadContent(file.getFileName(), content);
    }

    public List<FileView> listLatestByCategory(long taskId, FileCategory category, CurrentUser currentUser) {
        requireTaskAccess(taskId, currentUser, category, false);
        return fileMapper.findLatestByTaskIdAndCategory(taskId, category.name()).stream().map(FileView::from).toList();
    }

    private ReviewTaskRecord requireTaskAccess(long taskId, CurrentUser currentUser, FileCategory category, boolean upload) {
        if (!permissionPolicy.has(currentUser.roles(), upload ? category.uploadPermission() : category.downloadPermission())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无对应文件操作权限");
        }
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        if (upload && TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许上传新文件");
        }
        if (upload && category == FileCategory.PCB_SCHEMATIC && currentUser.roles().contains(Role.DESIGNER)
                && !task.getDesignerId().equals(currentUser.id())
                && !currentUser.roles().contains(Role.PCB_LEADER)
                && !currentUser.roles().contains(Role.HARDWARE_DEPARTMENT_MANAGER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者可以上传该任务的 PCB 或原理图文件");
        }
        if (!permissionPolicy.canViewAllTasks(currentUser.roles())
                && !taskAssignmentAccessMapper.isAssignedToTask(taskId, currentUser.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该任务的文件");
        }
        return task;
    }

    private synchronized long nextId() {
        return fileMapper.nextId();
    }

    private void requireBusinessFileKey(String businessFileKey) {
        if (businessFileKey == null || businessFileKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件业务标识不能为空");
        }
    }

    private void appendAudit(ReviewFileRecord file, String action, long operatorId) {
        auditMapper.insert(new OperationAuditRecord("REVIEW_FILE", file.getId(), action, operatorId,
                file.getFileCategory() + ":" + file.getFileName()));
    }

    private void persist(ReviewFileRecord record, ReviewFileRecord latest) {
        if (latest == null) {
            fileMapper.insert(record);
            return;
        }
        fileMapper.updateCurrent(record);
    }

    private ReviewFileRecord toRecord(long id, RegisterFileCommand command, long uploadedBy, String companyFileId, String uploadedStage) {
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(id);
        record.setTaskId(command.taskId());
        record.setFileCategory(command.category().name());
        record.setBusinessFileKey(command.businessFileKey());
        record.setFileName(command.fileName());
        record.setFileFormat(fileFormat(command.fileName()));
        record.setFileSize(command.fileSize());
        record.setMd5(command.md5());
        record.setCompanyFileId(companyFileId);
        record.setLatest(true);
        record.setUploadedBy(uploadedBy);
        record.setUploadedStage(uploadedStage);
        return record;
    }

    private ReviewFileRecord directRecord(long id, long taskId, String businessFileKey, MultipartFile file, String md5, long uploadedBy) {
        return directRecord(id, taskId, FileCategory.PCB_SCHEMATIC, businessFileKey, file, md5, uploadedBy, TaskStatus.DRAFT.name());
    }

    private ReviewFileRecord referenceRecord(long id, long taskId, FileCategory category, String businessFileKey,
                                             FileReferenceCommand file, long uploadedBy, String uploadedStage) {
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(id); record.setTaskId(taskId); record.setFileCategory(category.name()); record.setBusinessFileKey(businessFileKey);
        record.setFileName(file.fileName().trim()); record.setFileFormat(fileFormat(file.fileName())); record.setFileSize(file.fileSize());
        record.setMd5(file.md5() == null ? "" : file.md5());
        record.setCompanyFileId(file.companyFileId().trim()); record.setLatest(true); record.setUploadedBy(uploadedBy); record.setUploadedStage(uploadedStage);
        return record;
    }

    private ReviewFileRecord directRecord(long id, long taskId, FileCategory category, String businessFileKey, MultipartFile file, String md5,
                                          long uploadedBy, String uploadedStage) {
        ResourceServiceClient.StoredResource resource = resourceServiceClient == null
                ? new ResourceServiceClient.StoredResource("mock-inline-" + UUID.randomUUID(), "/mock/bms/pcb/" + file.getOriginalFilename())
                : resourceServiceClient.upload(file, taskId, category);
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(id); record.setTaskId(taskId); record.setFileCategory(category.name()); record.setBusinessFileKey(businessFileKey);
        record.setFileName(file.getOriginalFilename()); record.setFileFormat(fileFormat(file.getOriginalFilename())); record.setFileSize(file.getSize()); record.setMd5(md5);
        record.setCompanyFileId(resource.resourcePath()); record.setLatest(true); record.setUploadedBy(uploadedBy); record.setUploadedStage(uploadedStage);
        return record;
    }

    private String md5(MultipartFile file) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(file.getBytes());
            StringBuilder result = new StringBuilder();
            for (byte value : hash) { result.append(String.format("%02x", value)); }
            return result.toString();
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无法读取评审文件：" + exception.getMessage());
        }
    }

    private String fileFormat(String fileName) {
        int separator = fileName == null ? -1 : fileName.lastIndexOf('.');
        return separator < 0 || separator == fileName.length() - 1 ? "" : fileName.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
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

    /** 公司资源服务上传完成后，由前端随任务保存或提交请求带回的文件引用。 */
    public record FileReferenceCommand(String companyFileId, String fileName, long fileSize, String md5, String businessFileKey) {
        public FileReferenceCommand {
            if (companyFileId == null || companyFileId.isBlank() || fileName == null || fileName.isBlank() || fileSize < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "公司文件标识、文件名和文件大小不能为空");
            }
        }
    }

    public record UploadSessionView(String uploadSessionId, String uploadUrl) {
    }

    public record DownloadContent(String fileName, byte[] content) {
    }

    public record FileView(Long id, Long taskId, FileCategory category, String businessFileKey, String fileName, String fileFormat,
                           Long fileSize, String md5, String resourcePath, Long uploadedBy, java.time.LocalDateTime uploadedAt,
                           String uploadedStage, boolean latest) {
        static FileView from(ReviewFileRecord record) {
            return new FileView(record.getId(), record.getTaskId(), FileCategory.valueOf(record.getFileCategory()), record.getBusinessFileKey(),
                    record.getFileName(), record.getFileFormat(), record.getFileSize(), record.getMd5(), record.getCompanyFileId(), record.getUploadedBy(),
                    record.getUploadedAt(), record.getUploadedStage(), record.getLatest());
        }
    }

}
