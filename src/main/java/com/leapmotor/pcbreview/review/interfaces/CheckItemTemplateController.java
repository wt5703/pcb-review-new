package com.leapmotor.pcbreview.review.interfaces;

import com.leapmotor.pcbreview.common.ApiResponse;
import com.leapmotor.pcbreview.common.TraceIdFilter;
import com.leapmotor.pcbreview.identity.application.CurrentUserHolder;
import com.leapmotor.pcbreview.review.application.CheckItemTemplateApplicationService;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 提供本地 Mock 阶段的检查项模板新增入口，模板维护暂使用互检管理权限，后续可无缝替换为专属模板管理授权。
 */
@RestController
@RequestMapping("/check-item-templates")
public class CheckItemTemplateController {
    private final CheckItemTemplateApplicationService templateApplicationService;

    public CheckItemTemplateController(CheckItemTemplateApplicationService templateApplicationService) {
        this.templateApplicationService = templateApplicationService;
    }

    @PostMapping
    ApiResponse<CheckItemTemplateApplicationService.TemplateView> create(@Valid @RequestBody CreateTemplateRequest request,
                                                                           HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.create(new CheckItemTemplateApplicationService.CreateTemplateCommand(
                request.reviewType(), request.itemKey(), request.parentItemKey(), request.itemName(), request.sortNo()), CurrentUserHolder.require()),
                traceId(servletRequest));
    }

    @PutMapping("/{templateId}")
    ApiResponse<CheckItemTemplateApplicationService.TemplateView> update(@PathVariable long templateId,
                                                                           @Valid @RequestBody UpdateTemplateRequest request,
                                                                           HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.update(templateId, new CheckItemTemplateApplicationService.UpdateTemplateCommand(
                request.itemKey(), request.parentItemKey(), request.itemName(), request.sortNo(), request.enabled(), request.version()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    record CreateTemplateRequest(@NotNull ReviewType reviewType, @NotBlank String itemKey, String parentItemKey,
                                 @NotBlank String itemName, @PositiveOrZero int sortNo) {
    }

    record UpdateTemplateRequest(@NotBlank String itemKey, String parentItemKey, @NotBlank String itemName,
                                 @PositiveOrZero int sortNo, boolean enabled, @PositiveOrZero long version) {
    }
}
