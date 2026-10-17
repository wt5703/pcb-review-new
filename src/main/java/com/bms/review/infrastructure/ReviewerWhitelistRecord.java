package com.bms.review.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 白名单人员资料；reviewRole 用于关联查询时承载单个角色，不属于人员主表字段。
 */
public class ReviewerWhitelistRecord {
    private Long id;
    private String reviewRole;
    private String employeeNo;
    private String createdByEmployeeNo;
    private LocalDateTime createdAt;
    private Boolean deleted;
    private String deletedByEmployeeNo;
    private LocalDateTime deletedAt;
    private String displayName;
    private String email;
    private String mobile;
    private String departmentName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReviewRole() { return reviewRole; }
    public void setReviewRole(String reviewRole) { this.reviewRole = reviewRole; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
    public String getCreatedByEmployeeNo() { return createdByEmployeeNo; }
    public void setCreatedByEmployeeNo(String createdByEmployeeNo) { this.createdByEmployeeNo = createdByEmployeeNo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public String getDeletedByEmployeeNo() { return deletedByEmployeeNo; }
    public void setDeletedByEmployeeNo(String deletedByEmployeeNo) { this.deletedByEmployeeNo = deletedByEmployeeNo; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
}
