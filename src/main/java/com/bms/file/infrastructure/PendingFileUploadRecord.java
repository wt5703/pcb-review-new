package com.bms.file.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 保存尚未关联任务的公司资源文件元数据，供创建或保存任务时仅凭文件 UUID 绑定为初始评审文件。
 */
public class PendingFileUploadRecord {
    private String fileId;
    private String fileCategory;
    private String fileName;
    private String fileFormat;
    private Long fileSize;
    private String md5;
    private String resourcePath;
    private Long uploadedBy;
    private LocalDateTime uploadedAt;

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getFileCategory() { return fileCategory; }
    public void setFileCategory(String fileCategory) { this.fileCategory = fileCategory; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileFormat() { return fileFormat; }
    public void setFileFormat(String fileFormat) { this.fileFormat = fileFormat; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }
    public String getResourcePath() { return resourcePath; }
    public void setResourcePath(String resourcePath) { this.resourcePath = resourcePath; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}
