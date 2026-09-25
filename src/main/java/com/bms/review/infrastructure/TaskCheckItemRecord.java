package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 映射任务内互检检查项实例，只关联检查项模板 ID，保存检查结论、说明和富文本；检查项名称由模板表查询。
 */
public class TaskCheckItemRecord {
    private Long id;
    private Long taskId;
    private Long itemId;
    private Long parentId;
    private Integer sortNo;
    private String checkResult;
    private String comment;
    private String richText;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getCheckResult() { return checkResult; }
    public void setCheckResult(String checkResult) { this.checkResult = checkResult; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getRichText() { return richText; }
    public void setRichText(String richText) { this.richText = richText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
