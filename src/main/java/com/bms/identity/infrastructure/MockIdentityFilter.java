package com.bms.identity.infrastructure;

import com.bms.identity.application.CurrentUserHolder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 将用户中心返回的当前用户写入请求上下文；用户中心不可用时使用统一 Mock 用户，不接受前端用户 ID 或角色。
 */


@Component
@Order(1)
public class MockIdentityFilter implements Filter {
    private final UserCenterCurrentUserClient userCenterCurrentUserClient;

    public MockIdentityFilter(UserCenterCurrentUserClient userCenterCurrentUserClient) {
        this.userCenterCurrentUserClient = userCenterCurrentUserClient;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        CurrentUserHolder.set(userCenterCurrentUserClient.currentUser((HttpServletRequest) request));
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }

}
