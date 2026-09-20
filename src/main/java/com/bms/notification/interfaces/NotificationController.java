package com.bms.notification.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.notification.application.NotificationApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 提供本地阶段手动触发 Outbox 通知投递与重试的管理接口，权限暂复用用户管理权限，真实邮件收件人和模板待业务确认后替换。
 */
@RestController
@RequestMapping("/notifications")
@Tag(name = "通知投递", description = "管理本地 Mock 环境的 Outbox 通知投递和失败重试。")
public class NotificationController {
    private final NotificationApplicationService notificationApplicationService;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public NotificationController(NotificationApplicationService notificationApplicationService) {
        this.notificationApplicationService = notificationApplicationService;
    }

    @PostMapping("/dispatch")
    @Operation(summary = "手动投递待发送通知", description = "按批量大小和最大重试次数消费待投递 Outbox 事件；真实邮件模板与收件人仍待业务确认。")
    ApiResponse<NotificationApplicationService.DispatchResult> dispatch(@Valid @RequestBody DispatchRequest request,
                                                                          HttpServletRequest servletRequest) {
        if (!permissionPolicy.has(CurrentUserHolder.require().roles(), Permission.MANAGE_USER)) {
            throw new com.bms.common.BusinessException(com.bms.common.ErrorCode.FORBIDDEN, "无通知投递管理权限");
        }
        return ApiResponse.ok(notificationApplicationService.dispatch(request.limit(), request.maxRetries()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }

    @Schema(description = "通知投递请求")
    record DispatchRequest(@Schema(description = "本次最多处理的待投递事件数量", requiredMode = Schema.RequiredMode.REQUIRED) @Positive int limit,
                           @Schema(description = "单条通知允许的最大重试次数", requiredMode = Schema.RequiredMode.REQUIRED) @Positive int maxRetries) {
    }
}
