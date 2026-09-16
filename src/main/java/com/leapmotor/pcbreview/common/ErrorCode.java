package com.leapmotor.pcbreview.common;

/**
 * @author 王涛
 * @date 2026-09-09
 * @description 平台对外暴露的稳定业务错误码集合，用于区分参数错误、权限不足、资源不存在和状态流转冲突等失败类型。
 */


public enum ErrorCode {
    VALIDATION_ERROR,
    FORBIDDEN,
    RESOURCE_NOT_FOUND,
    TASK_STATUS_CONFLICT,
    OPINION_STATUS_CONFLICT,
    VERSION_CONFLICT,
    DUPLICATE_REQUEST,
    EXTERNAL_SERVICE_UNAVAILABLE
}
