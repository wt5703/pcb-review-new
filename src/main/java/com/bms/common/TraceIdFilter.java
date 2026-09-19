package com.bms.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * @author 王涛
 * @date 2026-09-09
 * @description 在每个 HTTP 请求进入时复用或生成追踪标识，并写入响应头及日志上下文以串联一次请求的诊断信息。
 */


@Component
public class TraceIdFilter implements Filter {
    public static final String TRACE_ID_ATTRIBUTE = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        ((HttpServletResponse) response).setHeader("X-Trace-Id", traceId);
        chain.doFilter(request, response);
    }
}
