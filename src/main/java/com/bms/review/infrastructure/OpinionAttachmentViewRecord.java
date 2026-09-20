package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 承载专家意见图片的前端回显信息，包含文件名称、排序、类别和文件服务提供的预览地址。
 */
public class OpinionAttachmentViewRecord {
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
