package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 承载互检检查项附件的前端回显信息，包含文件名称、显示顺序和文件服务预览地址，不复制文件二进制内容。
 */
public class CheckItemAttachmentViewRecord {
    private Long fileId;
    private Integer sortNo;
    private String fileName;
    private String fileCategory;
    private String previewUrl;

    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileCategory() { return fileCategory; }
    public void setFileCategory(String fileCategory) { this.fileCategory = fileCategory; }
    public String getPreviewUrl() { return previewUrl; }
    public void setPreviewUrl(String previewUrl) { this.previewUrl = previewUrl; }
}
