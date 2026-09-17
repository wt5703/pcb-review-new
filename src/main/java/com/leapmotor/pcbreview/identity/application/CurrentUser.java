package com.leapmotor.pcbreview.identity.application;

import com.leapmotor.pcbreview.identity.domain.Role;

import java.util.Set;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 表示当前请求已认证用户的不可变上下文，包含用户标识与角色集合，供应用服务执行权限和数据范围判断。
 */


public record CurrentUser(Long id, Set<Role> roles) {
}
