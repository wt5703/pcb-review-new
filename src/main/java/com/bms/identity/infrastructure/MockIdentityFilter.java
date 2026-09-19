package com.bms.identity.infrastructure;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.CurrentUserHolder;
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
 * @description 仅供本地 Mock 环境使用的身份注入过滤器，从请求头读取模拟用户和角色并建立当前请求的用户上下文，不替代生产认证机制。
 */


@Component
@Order(1)
public class MockIdentityFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        Long userId = Long.valueOf(httpRequest.getHeader("X-Mock-User-Id") == null ? "1" : httpRequest.getHeader("X-Mock-User-Id"));
        String roleHeader = httpRequest.getHeader("X-Mock-Roles");
        Set<Role> roles = roleHeader == null || roleHeader.isBlank()
                ? Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)
                : Arrays.stream(roleHeader.split(",")).map(String::trim).map(Role::valueOf).collect(Collectors.toUnmodifiableSet());
        CurrentUserHolder.set(new CurrentUser(userId, roles));
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }
}
