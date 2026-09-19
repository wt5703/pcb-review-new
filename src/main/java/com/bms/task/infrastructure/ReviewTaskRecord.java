package com.bms.task.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 与 review_task 表字段一一对应的数据记录对象，仅负责任务聚合的数据库读写映射，不包含领域状态流转行为。
 */
public class ReviewTaskRecord {
    private Long id;
    private String reviewType;
    private String taskName;
    private String projectName;
    private Long designerId;
    private String designName;
    private String pcbType;
    private String status;
    private String initialFileIds;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReviewType() { return reviewType; }
    public void setReviewType(String reviewType) { this.reviewType = reviewType; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public Long getDesignerId() { return designerId; }
    public void setDesignerId(Long designerId) { this.designerId = designerId; }
    public String getDesignName() { return designName; }
    public void setDesignName(String designName) { this.designName = designName; }
    public String getPcbType() { return pcbType; }
    public void setPcbType(String pcbType) { this.pcbType = pcbType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInitialFileIds() { return initialFileIds; }
    public void setInitialFileIds(String initialFileIds) { this.initialFileIds = initialFileIds; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
