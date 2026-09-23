package com.bms.file.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 与 review_file 表对应的文件持久化记录，保存 PCB 文件标识、资源路径和文件元数据，不保存文件二进制内容。
 */
public class ReviewFileRecord {
    private Long id;
    private Long taskId;
    private String fileCategory;
    private String businessFileKey;
    private String fileName;
    private String fileFormat;
    private Long fileSize;
    private String md5;
    private String fileId;
    private String resourcePath;
    private Boolean latest;
    private Long uploadedBy;
    private LocalDateTime uploadedAt;
    private String uploadedStage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getFileCategory() { return fileCategory; }
    public void setFileCategory(String fileCategory) { this.fileCategory = fileCategory; }
    public String getBusinessFileKey() { return businessFileKey; }
    public void setBusinessFileKey(String businessFileKey) { this.businessFileKey = businessFileKey; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileFormat() { return fileFormat; }
    public void setFileFormat(String fileFormat) { this.fileFormat = fileFormat; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getResourcePath() { return resourcePath; }
    public void setResourcePath(String resourcePath) { this.resourcePath = resourcePath; }
    public Boolean getLatest() { return latest; }
    public void setLatest(Boolean latest) { this.latest = latest; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    public String getUploadedStage() { return uploadedStage; }
    public void setUploadedStage(String uploadedStage) { this.uploadedStage = uploadedStage; }
}
