package com.bms.identity.application;

import com.bms.identity.domain.Role;

import java.util.Set;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 表示当前请求已认证用户的不可变上下文，包含用户标识与角色集合，供应用服务执行权限和数据范围判断。
 */


public record CurrentUser(String employeeNo, String displayName, String email, String mobile,
                          String departmentName, Set<Role> roles) {
    public CurrentUser(String employeeNo, String displayName, Set<Role> roles) {
        this(employeeNo, displayName, null, null, null, roles);
    }

    public CurrentUser(String employeeNo, Set<Role> roles) {
        this(employeeNo, employeeNo, null, null, null, roles);
    }

    public String resolvedDisplayName() {
        return displayName == null || displayName.isBlank() ? employeeNo : displayName;
    }
}
