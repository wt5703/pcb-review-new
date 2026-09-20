package com.bms.task.infrastructure;

import java.time.LocalDate;

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
    private String designerName;
    private String designName;
    private String pcbType;
    private LocalDate expectedCompletedDate;
    private Long expertLeaderId;
    private String expertLeaderName;
    private String reviewRoles;
    private String reviewDescription;
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
    public String getDesignerName() { return designerName; }
    public void setDesignerName(String designerName) { this.designerName = designerName; }
    public String getDesignName() { return designName; }
    public void setDesignName(String designName) { this.designName = designName; }
    public String getPcbType() { return pcbType; }
    public void setPcbType(String pcbType) { this.pcbType = pcbType; }
    public LocalDate getExpectedCompletedDate() { return expectedCompletedDate; }
    public void setExpectedCompletedDate(LocalDate expectedCompletedDate) { this.expectedCompletedDate = expectedCompletedDate; }
    public Long getExpertLeaderId() { return expertLeaderId; }
    public void setExpertLeaderId(Long expertLeaderId) { this.expertLeaderId = expertLeaderId; }
    public String getExpertLeaderName() { return expertLeaderName; }
    public void setExpertLeaderName(String expertLeaderName) { this.expertLeaderName = expertLeaderName; }
    public String getReviewRoles() { return reviewRoles; }
    public void setReviewRoles(String reviewRoles) { this.reviewRoles = reviewRoles; }
    public String getReviewDescription() { return reviewDescription; }
    public void setReviewDescription(String reviewDescription) { this.reviewDescription = reviewDescription; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInitialFileIds() { return initialFileIds; }
    public void setInitialFileIds(String initialFileIds) { this.initialFileIds = initialFileIds; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
