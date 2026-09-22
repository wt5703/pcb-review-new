package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 白名单与本地用户目录关联后的可分配人员读取模型，不承载任务实际分配状态。
 */
public class AssignableReviewerRecord {
    private String whitelistRole;
    private Long userId;
    private String employeeNo;
    private String displayName;
    private String departmentName;

    public String getWhitelistRole() { return whitelistRole; }
    public void setWhitelistRole(String whitelistRole) { this.whitelistRole = whitelistRole; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
}
