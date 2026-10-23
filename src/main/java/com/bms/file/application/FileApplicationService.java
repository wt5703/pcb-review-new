package com.bms.file.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.domain.FileCategory;
import com.bms.file.infrastructure.ResourceServiceClient;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
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
 * @description 编排任务文件上传、文件元数据登记和下载授权；每个公司文件标识独立保存，不维护业务文件键或文件版本号。
 */
@Service
public class FileApplicationService {
    private final ReviewFileMapper fileMapper;
    private final ReviewTaskMapper taskMapper;
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper;
    private final ResourceServiceClient resourceServiceClient;

    @Autowired
    public FileApplicationService(ReviewFileMapper fileMapper, ReviewTaskMapper taskMapper,
                                  TaskAssignmentAccessMapper taskAssignmentAccessMapper,
                                  ResourceServiceClient resourceServiceClient) {
        this.fileMapper = fileMapper;
        this.taskMapper = taskMapper;
        this.taskAssignmentAccessMapper = taskAssignmentAccessMapper;
        this.resourceServiceClient = resourceServiceClient;
    }

    /**
     * @author 王涛
     * @date 2026-09-22
     * @description 将创建任务前上传的多个文件 UUID 绑定至任务；未绑定文件同样保存于 review_file，taskId 为空表示尚未关联任务。
     */
    @Transactional
    public List<FileView> bindPendingInitialFiles(long taskId, List<String> fileIds, String operatorEmployeeNo, String operatorName) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        ReviewTaskRecord task = requireTask(taskId);
        FileCategory initialCategory = initialCategory(task);
        requireTaskAccess(taskId, operatorEmployeeNo, operatorName, initialCategory, true);
        java.util.Set<String> requestFileIds = new java.util.LinkedHashSet<>();
        List<FileView> result = new ArrayList<>();
        for (String fileId : fileIds) {
            String normalizedFileId = requireFileUuid(fileId);
            if (!requestFileIds.add(normalizedFileId)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "初始文件 UUID 不能为空且不能重复");
            }
            ReviewFileRecord pending = fileMapper.findByFileId(normalizedFileId);
            if (pending == null || !initialCategory.name().equals(pending.getFileCategory())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "初始文件 UUID 不存在或不可用于创建任务");
            }
            if (pending.getTaskId() != null) {
                // 编辑草稿时，前端会连同已回显文件一起提交。该文件已绑定到当前任务即视为幂等成功，
                // 直接返回既有记录，既不重复更新，也不再创建任何文件关联。
                if (Long.valueOf(taskId).equals(pending.getTaskId())) {
                    result.add(FileView.from(pending));
                    continue;
                }
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该文件已绑定到其他任务，不能重复关联");
            }
            if (!operatorEmployeeNo.equals(pending.getUploadedByEmployeeNo())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "仅文件上传人可以将该文件关联到任务");
            }
            pending.setTaskId(taskId);
            pending.setLatest(true);
            pending.setUploadedStage(TaskStatus.DRAFT.name());
            if (fileMapper.bindToTask(pending) != 1) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该任务已保存对应文件，不能重复保存");
            }
            result.add(FileView.from(pending));
        }
        return List.copyOf(result);
    }

    /** 上传尚未创建任务的初始文件，持久化元数据并返回前端后续保存任务唯一需要携带的 UUID。 */
    @Transactional
    public UploadedFileView uploadPendingInitialFile(MultipartFile file, FileCategory category, String operatorEmployeeNo, String operatorName) {
        if (category != FileCategory.PCB_REVIEW && category != FileCategory.SCHEMATIC_REVIEW) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未关联任务的上传仅允许 PCB_REVIEW 或 SCHEMATIC_REVIEW");
        }
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "上传文件不能为空");
        }
        String fileId = UUID.randomUUID().toString();
        ResourceServiceClient.StoredResource resource = resourceServiceClient.upload(file, fileId);
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(nextId()); record.setFileId(resource.resourceId()); record.setTaskId(null); record.setFileCategory(category.name());
        record.setFileName(file.getOriginalFilename());
        record.setFileFormat(fileFormat(file.getOriginalFilename())); record.setFileSize(file.getSize()); record.setMd5(md5(file));
        record.setResourcePath(resource.resourcePath()); record.setLatest(true); record.setUploadedByEmployeeNo(operatorEmployeeNo); record.setUploadedStage(null);
        fileMapper.insert(record);
        return new UploadedFileView(record.getFileId(), record.getId(), record.getFileName(), record.getFileSize(), category);
    }

    @Transactional
    public FileView upload(long taskId, FileCategory category, MultipartFile file, String operatorEmployeeNo, String operatorName) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "上传文件不能为空");
        }
        ReviewTaskRecord task = requireTaskAccess(taskId, operatorEmployeeNo, operatorName, category, true);
        String md5 = md5(file);
        String fileId = UUID.randomUUID().toString();
        ResourceServiceClient.StoredResource resource = resourceServiceClient.upload(file, fileId);
        ReviewFileRecord record = new ReviewFileRecord();
        record.setId(nextId());
        record.setTaskId(taskId);
        record.setFileCategory(category.name());
        record.setFileName(file.getOriginalFilename());
        record.setFileFormat(fileFormat(file.getOriginalFilename()));
        record.setFileSize(file.getSize());
        record.setMd5(md5);
        record.setFileId(resource.resourceId());
        record.setResourcePath(resource.resourcePath());
        record.setLatest(true);
        record.setUploadedByEmployeeNo(operatorEmployeeNo);
        record.setUploadedStage(task.getStatus());
        // 仅当资源服务上传成功后才淘汰旧版本，保证上传异常时原最新文件仍可继续下载和评审。
        fileMapper.markLatestAsHistorical(taskId, category.name());
        fileMapper.insert(record);
        return FileView.from(record);
    }

    /**
     * 按文件主键下载。任务归属由文件记录决定，调用方不需要、也不能重复传 taskId。
     */
    public DownloadContent downloadFile(long fileId, String operatorEmployeeNo, String operatorName) {
        ReviewFileRecord file = fileMapper.findById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        requireTaskAccess(file.getTaskId(), operatorEmployeeNo, operatorName, FileCategory.valueOf(file.getFileCategory()), false);
        byte[] content = resourceServiceClient.download(file.getFileName(), file.getResourcePath());
        return new DownloadContent(file.getFileName(), content);
    }

    public List<FileView> listLatestByCategory(long taskId, FileCategory category, String operatorEmployeeNo, String operatorName) {
        requireTaskAccess(taskId, operatorEmployeeNo, operatorName, category, false);
        return fileMapper.findLatestByTaskIdAndCategory(taskId, category.name()).stream().map(FileView::from).toList();
    }

    /**
     * 取消用户刚上传的文件与任务之间的关联。文件元数据和公司资源均保留，避免误删可恢复的上传内容。
     */
    @Transactional
    public void unbindFile(long taskId, String fileId, String operatorEmployeeNo, String operatorName) {
        String normalizedFileId = requireFileUuid(fileId);
        ReviewFileRecord file = fileMapper.findByFileId(normalizedFileId);
        if (file == null || !Long.valueOf(taskId).equals(file.getTaskId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "当前任务不存在该文件");
        }
        ReviewTaskRecord task = requireTaskAccess(taskId, operatorEmployeeNo, operatorName,
                FileCategory.valueOf(file.getFileCategory()), false);
        if (TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许取消文件上传");
        }
        if (!operatorEmployeeNo.equals(task.getDesignerEmployeeNo())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者可以取消文件上传");
        }
        if (fileMapper.unbindFromTask(taskId, normalizedFileId) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "当前任务不存在该文件");
        }
        restorePreviousLatestFile(taskId, file);
        // 初始文件主键属于任务表的冗余字段；解除关联后必须同步清理，避免草稿误认为仍有该文件。
        taskMapper.removeInitialFileId(taskId, file.getId());
    }

    private void restorePreviousLatestFile(long taskId, ReviewFileRecord cancelledFile) {
        if (!Boolean.TRUE.equals(cancelledFile.getLatest())) {
            return;
        }
        ReviewFileRecord previousFile = fileMapper.findLatestHistoricalByTaskIdAndCategory(taskId,
                cancelledFile.getFileCategory());
        if (previousFile != null) {
            fileMapper.markAsLatest(taskId, previousFile.getId());
        }
    }

    private ReviewTaskRecord requireTaskAccess(long taskId, String operatorEmployeeNo, String operatorName, FileCategory category, boolean upload) {
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        if (upload && TaskStatus.FINISHED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许上传新文件");
        }
        if (upload && (category == FileCategory.PCB_REVIEW || category == FileCategory.SCHEMATIC_REVIEW
                || category == FileCategory.PCB_PROCESS_REVIEW || category == FileCategory.PCB_STRUCTURE_REVIEW)
                && !task.getDesignerEmployeeNo().equals(operatorEmployeeNo)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务设计者可以上传该任务的 PCB 或原理图文件");
        }
        if (!operatorEmployeeNo.equals(task.getDesignerEmployeeNo())
                && !operatorEmployeeNo.equals(task.getExpertLeaderEmployeeNo())
                && !taskAssignmentAccessMapper.isAssignedToTask(taskId, operatorEmployeeNo)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该任务的文件");
        }
        return task;
    }

    private synchronized long nextId() {
        return fileMapper.nextId();
    }

    private ReviewTaskRecord requireTask(long taskId) {
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        return task;
    }

    private FileCategory initialCategory(ReviewTaskRecord task) {
        return "PCB".equals(task.getReviewType()) ? FileCategory.PCB_REVIEW : FileCategory.SCHEMATIC_REVIEW;
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

    private String requireFileUuid(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "初始文件 UUID 不能为空");
        }
        try {
            return UUID.fromString(fileId.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "初始文件标识必须是 UUID");
        }
    }

    /** 创建任务前上传文件的最小返回模型；保存或提交任务仅回传 fileId UUID 数组。 */
    public record UploadedFileView(String fileId, Long taskFileId, String fileName, long fileSize, FileCategory fileCategory) { }

    public record DownloadContent(String fileName, byte[] content) {
    }

    public record FileView(Long id, Long taskId, FileCategory category, String fileName, String fileFormat,
                           Long fileSize, String md5, String fileId, String resourcePath, String uploadedByEmployeeNo, java.time.LocalDateTime uploadedAt,
                           String uploadedStage, boolean latest) {
        static FileView from(ReviewFileRecord record) {
            return new FileView(record.getId(), record.getTaskId(), FileCategory.valueOf(record.getFileCategory()), record.getFileName(),
                    record.getFileFormat(), record.getFileSize(), record.getMd5(), record.getFileId(), record.getResourcePath(), record.getUploadedByEmployeeNo(),
                    record.getUploadedAt(), record.getUploadedStage(), Boolean.TRUE.equals(record.getLatest()));
        }
    }

}
