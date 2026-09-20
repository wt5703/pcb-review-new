package com.bms.review.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.application.CheckItemTemplateApplicationService;
import com.bms.task.domain.ReviewType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotEmpty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 提供本地 Mock 阶段的检查项模板新增入口，模板维护暂使用互检管理权限，后续可无缝替换为专属模板管理授权。
 */
@RestController
@RequestMapping("/check-item-templates")
@Tag(name = "互检检查项模板", description = "按“检查项大类—检查项子类”一对多结构维护互检模板，并支持 Excel 批量导入。")
public class CheckItemTemplateController {
    private final CheckItemTemplateApplicationService templateApplicationService;

    public CheckItemTemplateController(CheckItemTemplateApplicationService templateApplicationService) {
        this.templateApplicationService = templateApplicationService;
    }

    @GetMapping
    @Operation(summary = "查询互检检查项模板", description = "按可选评审类型读取检查项大类及子检查项的树形结构。结果包含启用状态和乐观锁版本，供互检管理页面编辑或停用。")
    ApiResponse<List<CheckItemTemplateApplicationService.TemplateCategoryView>> list(
            @Parameter(description = "可选评审类型；不传时返回 PCB 与原理图模板") @RequestParam(required = false) ReviewType reviewType,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.list(reviewType, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping
    @Operation(summary = "新增检查项大类及子检查项", description = "一次新增一个检查项大类及其一个或多个子检查项；大类和子类均会保存编码、名称、排序和启用状态。")
    ApiResponse<CheckItemTemplateApplicationService.TemplateCategoryView> create(@Valid @RequestBody CreateTemplateRequest request,
                                                                           HttpServletRequest servletRequest) {
        boolean legacyFlatItem = request.items() == null || request.items().isEmpty();
        List<TemplateItemRequest> items = legacyFlatItem ? List.of(new TemplateItemRequest(request.categoryKey(), request.categoryName(), request.sortNo(), true)) : request.items();
        return ApiResponse.ok(templateApplicationService.create(new CheckItemTemplateApplicationService.CreateCategoryCommand(
                request.reviewType(), legacyFlatItem ? request.categoryKey() + "_CATEGORY" : request.categoryKey(), legacyFlatItem ? "默认类别" : request.categoryName(), request.sortNo(), items.stream()
                        .map(item -> new CheckItemTemplateApplicationService.CreateItemCommand(item.itemKey(), item.itemName(), item.sortNo(), item.enabled())).toList()), CurrentUserHolder.require()),
                traceId(servletRequest));
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @Operation(summary = "Excel 批量导入互检检查项模板", description = "导入首个工作表。兼容旧表头“评审类型、检查项编码、父级检查项编码、检查项名称、排序号、是否启用”，也支持类别/检查项中文列名。任一行错误则整体回滚。")
    ApiResponse<CheckItemTemplateApplicationService.ImportResult> importWorkbook(@RequestPart("file") MultipartFile file,
                                                                                  HttpServletRequest servletRequest) throws IOException {
        return ApiResponse.ok(templateApplicationService.importWorkbook(file.getBytes(), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping("/{templateId}")
    @Operation(summary = "修改检查项大类及子检查项", description = "以检查项大类 ID 为目标，支持同时更新大类及多个已有或新增的子检查项；已存在的子检查项必须携带其版本号。")
    ApiResponse<CheckItemTemplateApplicationService.TemplateCategoryView> update(@PathVariable long templateId,
                                                                           @Valid @RequestBody UpdateTemplateRequest request,
                                                                           HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.update(templateId, new CheckItemTemplateApplicationService.UpdateCategoryCommand(
                request.categoryKey(), request.categoryName(), request.sortNo(), request.enabled(), request.version(), request.items().stream()
                        .map(item -> new CheckItemTemplateApplicationService.UpdateItemCommand(item.id(), item.itemKey(), item.itemName(), item.sortNo(), item.enabled(), item.version())).toList()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @DeleteMapping("/{templateId}")
    @Operation(summary = "停用检查项大类", description = "逻辑停用一个检查项大类及全部子检查项，不物理删除历史定义；已创建任务仍按任务自身的模板同步和归档规则处理。")
    ApiResponse<Void> disable(@PathVariable long templateId,
                              @Parameter(description = "检查项大类当前乐观锁版本号", required = true) @RequestParam @PositiveOrZero long version,
                              HttpServletRequest servletRequest) {
        templateApplicationService.disable(templateId, version, CurrentUserHolder.require());
        return ApiResponse.ok(null, traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "新增检查项类别请求")
    record CreateTemplateRequest(@Schema(description = "适用评审类型", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewType reviewType,
                                 @Schema(description = "检查项大类编码，在同一评审类型内唯一", requiredMode = Schema.RequiredMode.REQUIRED) @JsonAlias("itemKey") @NotBlank String categoryKey,
                                 @Schema(description = "检查项大类名称", requiredMode = Schema.RequiredMode.REQUIRED) @JsonAlias("itemName") @NotBlank String categoryName,
                                 @Schema(description = "大类展示排序号，从 0 开始") @PositiveOrZero int sortNo,
                                 @Schema(description = "该大类包含的检查项子类，至少一项", requiredMode = Schema.RequiredMode.REQUIRED) List<@Valid TemplateItemRequest> items) {
    }

    @Schema(description = "修改检查项类别请求")
    record UpdateTemplateRequest(@Schema(description = "检查项大类编码", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String categoryKey,
                                 @Schema(description = "检查项大类名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String categoryName,
                                 @Schema(description = "大类展示排序号") @PositiveOrZero int sortNo,
                                 @Schema(description = "大类是否启用") boolean enabled,
                                 @Schema(description = "大类当前乐观锁版本号", requiredMode = Schema.RequiredMode.REQUIRED) @PositiveOrZero long version,
                                 @Schema(description = "修改后的检查项子类列表，至少一项", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<@Valid UpdateTemplateItemRequest> items) {
    }
    @Schema(description = "检查项子类内容")
    record TemplateItemRequest(@Schema(description = "检查项编码，在同一评审类型内唯一", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemKey,
                               @Schema(description = "检查项名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemName,
                               @Schema(description = "子类展示排序号") @PositiveOrZero int sortNo,
                               @Schema(description = "子类是否启用") boolean enabled) { }
    @Schema(description = "修改检查项子类内容")
    record UpdateTemplateItemRequest(@Schema(description = "已有子检查项 ID；新增子检查项不传") Long id,
                                     @Schema(description = "检查项编码", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemKey,
                                     @Schema(description = "检查项名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemName,
                                     @Schema(description = "子类展示排序号") @PositiveOrZero int sortNo,
                                     @Schema(description = "是否启用") boolean enabled,
                                     @Schema(description = "已有子检查项的乐观锁版本号；新增项不传") Long version) { }
}
