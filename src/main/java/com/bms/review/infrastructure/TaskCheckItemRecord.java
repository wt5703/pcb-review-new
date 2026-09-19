package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 映射任务内互检检查项实例，保存同步得到的模板快照、检查结论、说明和关联意见标识，供已结束任务稳定回溯。
 */
public class TaskCheckItemRecord {
    private Long id;
    private Long taskId;
    private Long templateItemId;
    private String templateItemKey;
    private String parentItemKey;
    private String itemName;
    private Integer sortNo;
    private String checkResult;
    private String comment;
    private Long linkedOpinionId;
    private String status;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getTemplateItemId() { return templateItemId; }
    public void setTemplateItemId(Long templateItemId) { this.templateItemId = templateItemId; }
    public String getTemplateItemKey() { return templateItemKey; }
    public void setTemplateItemKey(String templateItemKey) { this.templateItemKey = templateItemKey; }
    public String getParentItemKey() { return parentItemKey; }
    public void setParentItemKey(String parentItemKey) { this.parentItemKey = parentItemKey; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getCheckResult() { return checkResult; }
    public void setCheckResult(String checkResult) { this.checkResult = checkResult; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Long getLinkedOpinionId() { return linkedOpinionId; }
    public void setLinkedOpinionId(Long linkedOpinionId) { this.linkedOpinionId = linkedOpinionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
