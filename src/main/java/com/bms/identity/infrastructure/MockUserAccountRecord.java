package com.bms.identity.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射本地 Mock 用户目录中的账号基本信息，仅用于开发联调身份选择，不承载生产认证凭据。
 */
public class MockUserAccountRecord {
    private Long id;
    private String displayName;
    private String email;
    private String departmentName;
    private Boolean enabled;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
