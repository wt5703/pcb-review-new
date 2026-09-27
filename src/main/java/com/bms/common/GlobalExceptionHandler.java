package com.bms.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * @author 王涛
 * @date 2026-09-09
 * @description 集中拦截控制器抛出的业务异常、参数校验异常和未知异常，并映射为统一且不泄露内部实现的 HTTP 错误响应。
 */


@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception, HttpServletRequest request) {
        return ResponseEntity.status(status(exception.errorCode()))
                .body(new ErrorResponse(exception.errorCode().name(), exception.getMessage(), traceId(request)));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ErrorResponse> handleValidation(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR.name(), "请求参数不合法", traceId(request)));
    }

    /** 将超出 multipart 上限转换为前端可读取的 JSON，而不是代理层的连接失败。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR.name(), "上传文件不能超过 200MB", traceId(request)));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> handleConflict(IllegalStateException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ErrorCode.TASK_STATUS_CONFLICT.name(), exception.getMessage(), traceId(request)));
    }

    private HttpStatus status(ErrorCode code) {
        return switch (code) {
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TASK_STATUS_CONFLICT, OPINION_STATUS_CONFLICT, VERSION_CONFLICT, DUPLICATE_REQUEST -> HttpStatus.CONFLICT;
            case EXTERNAL_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
