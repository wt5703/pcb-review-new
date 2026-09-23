package com.bms.review.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 对应评审人员白名单的一条角色—员工工号映射；同一评审角色可关联多个员工工号。
 */
public class ReviewerWhitelistRecord {
    private Long id;
    private String reviewRole;
    private String employeeNo;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Boolean deleted;
    private Long deletedBy;
    private LocalDateTime deletedAt;
    private String displayName;
    private String email;
    private String mobile;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReviewRole() { return reviewRole; }
    public void setReviewRole(String reviewRole) { this.reviewRole = reviewRole; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
}
