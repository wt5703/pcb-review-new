package com.leapmotor.pcbreview.file.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-16
 * @description 与 review_file 表对应的文件版本持久化记录，保存文件元数据、版本链定位和公司文件服务标识，不保存文件二进制内容。
 */
public class ReviewFileRecord {
    private Long id;
    private Long taskId;
    private String fileCategory;
    private String businessFileKey;
    private String fileName;
    private Long fileSize;
    private String md5;
    private Integer versionNo;
    private String companyFileId;
    private Boolean latest;
    private Long uploadedBy;

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
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getCompanyFileId() { return companyFileId; }
    public void setCompanyFileId(String companyFileId) { this.companyFileId = companyFileId; }
    public Boolean getLatest() { return latest; }
    public void setLatest(Boolean latest) { this.latest = latest; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
}
