package com.bms.common;

/**
 * @author 王涛
 * @date 2026-09-09
 * @description 承载可预期业务失败原因的运行时异常，将领域校验、权限拒绝和状态冲突统一转换为标准错误码。
 */


public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
