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

/** 将用户中心解析出的当前用户写入请求上下文，并在请求结束后清理线程变量。 */
@Component
@Order(1)
public class CurrentUserContextFilter implements Filter {
    private final UserCenterCurrentUserClient userCenterCurrentUserClient;

    public CurrentUserContextFilter(UserCenterCurrentUserClient userCenterCurrentUserClient) {
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
