package com.bms.common;

/**
 * @author 王涛
 * @date 2026-09-09
 * @description REST 接口失败响应的统一数据结构，向调用方返回错误码、可读错误信息和关联的请求追踪标识。
 */


public record ErrorResponse(String code, String message, String traceId) {
}
