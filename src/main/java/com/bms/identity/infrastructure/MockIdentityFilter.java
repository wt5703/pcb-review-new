package com.bms.identity.infrastructure;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.identity.application.MockUserDirectoryApplicationService;
import com.bms.identity.domain.Role;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 仅供本地 Mock 环境使用的身份注入过滤器，优先采用请求头覆盖的模拟角色，未传角色时从初始化账号目录解析，不替代生产认证机制。
 */


@Component
@Order(1)
public class MockIdentityFilter implements Filter {
    private final MockUserDirectoryApplicationService mockUserDirectoryApplicationService;

    public MockIdentityFilter(MockUserDirectoryApplicationService mockUserDirectoryApplicationService) {
        this.mockUserDirectoryApplicationService = mockUserDirectoryApplicationService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        Long userId = Long.valueOf(httpRequest.getHeader("X-Mock-User-Id") == null ? "1" : httpRequest.getHeader("X-Mock-User-Id"));
        String roleHeader = httpRequest.getHeader("X-Mock-Roles");
        Set<Role> roles = roleHeader == null || roleHeader.isBlank()
                ? mockUserDirectoryApplicationService.findRoles(userId)
                : Arrays.stream(roleHeader.split(",")).map(String::trim).map(Role::valueOf).collect(Collectors.toUnmodifiableSet());
        if (roles.isEmpty()) {
            roles = Set.of(Role.HARDWARE_DEPARTMENT_MANAGER);
        }
        CurrentUserHolder.set(new CurrentUser(userId, mockUserDirectoryApplicationService.findDisplayName(userId), roles));
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }
}
