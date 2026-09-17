package com.leapmotor.pcbreview.notification.interfaces;

import com.leapmotor.pcbreview.common.ApiResponse;
import com.leapmotor.pcbreview.common.TraceIdFilter;
import com.leapmotor.pcbreview.identity.application.CurrentUserHolder;
import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.notification.application.NotificationApplicationService;
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
public class NotificationController {
    private final NotificationApplicationService notificationApplicationService;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public NotificationController(NotificationApplicationService notificationApplicationService) {
        this.notificationApplicationService = notificationApplicationService;
    }

    @PostMapping("/dispatch")
    ApiResponse<NotificationApplicationService.DispatchResult> dispatch(@Valid @RequestBody DispatchRequest request,
                                                                          HttpServletRequest servletRequest) {
        if (!permissionPolicy.has(CurrentUserHolder.require().roles(), Permission.MANAGE_USER)) {
            throw new com.leapmotor.pcbreview.common.BusinessException(com.leapmotor.pcbreview.common.ErrorCode.FORBIDDEN, "无通知投递管理权限");
        }
        return ApiResponse.ok(notificationApplicationService.dispatch(request.limit(), request.maxRetries()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }

    record DispatchRequest(@Positive int limit, @Positive int maxRetries) {
    }
}
