package com.leapmotor.pcbreview.common;

/**
 * @author 王涛
 * @date 2026-09-09
 * @description REST 接口成功响应的统一信封结构，承载业务数据、成功标识与可供调用方追踪的请求标识。
 */


public record ApiResponse<T>(T data, String traceId) {
    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(data, traceId);
    }
}
