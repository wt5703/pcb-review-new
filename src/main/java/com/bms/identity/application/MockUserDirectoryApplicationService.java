package com.bms.identity.application;

import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.MockUserAccountMapper;
import com.bms.identity.infrastructure.MockUserAccountRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 提供本地 Mock 用户目录查询与角色解析，生产环境应由企业统一身份目录实现替代，不使用此服务进行账号认证。
 */
@Service
public class MockUserDirectoryApplicationService {
    private final MockUserAccountMapper userAccountMapper;

    public MockUserDirectoryApplicationService(MockUserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    public List<MockUserView> listEnabledUsers() {
        return userAccountMapper.findEnabled().stream().map(this::toView).toList();
    }

    public Set<Role> findRoles(long userId) {
        if (userAccountMapper.findEnabledById(userId) == null) {
            return Set.of();
        }
        return userAccountMapper.findRoleCodesByUserId(userId).stream().map(Role::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private MockUserView toView(MockUserAccountRecord account) {
        return new MockUserView(account.getId(), account.getDisplayName(), account.getEmail(), account.getDepartmentName(),
                userAccountMapper.findRoleCodesByUserId(account.getId()));
    }

    @Schema(description = "本地 Mock 初始化账号信息")
    public record MockUserView(@Schema(description = "本地 Mock 用户唯一 ID，也是 X-Mock-User-Id 请求头值") Long id,
                               @Schema(description = "人员中文显示名称") String displayName,
                               @Schema(description = "仅用于开发联调的测试邮箱地址") String email,
                               @Schema(description = "所属部门名称") String departmentName,
                               @Schema(description = "该人员预设的系统角色编码集合，例如 EMC_EXPERT") List<String> roles) { }
}
