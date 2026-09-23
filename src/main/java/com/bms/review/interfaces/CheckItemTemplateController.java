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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
    @Operation(summary = "查询互检检查项模板", description = "按可选评审类型返回 [{ category, items }] 的树形结构。category 与 items 均仅返回 id、itemName、sortNo")
    ApiResponse<List<CheckItemTemplateApplicationService.TemplateListCategoryView>> list(
            @Parameter(description = "可选评审类型；不传时返回 PCB 与原理图模板") @RequestParam(required = false) ReviewType reviewType,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.list(reviewType, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping
    @Operation(summary = "新增检查项大类及子检查项", description = "一次新增一个检查项大类及其可选的子检查项。categoryName 必填")
    ApiResponse<CheckItemTemplateApplicationService.TemplateCategoryView> create(@Valid @RequestBody CreateTemplateRequest request,
                                                                           HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.create(new CheckItemTemplateApplicationService.CreateCategoryCommand(
                request.reviewType(), request.categoryName(), (request.items() == null ? List.<TemplateItemRequest>of() : request.items()).stream()
                        .map(item -> new CheckItemTemplateApplicationService.CreateItemCommand(item.itemName())).toList()), CurrentUserHolder.require()),
                traceId(servletRequest));
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @Operation(summary = "Excel 批量导入互检检查项模板", description = "导入首个工作表，支持“类别、检查项”列和可选“评审类型”列。检查项单元格可按行首 1)、2) 编号拆分为多个子项；不读取或保存检查项编码。任一行错误则整体回滚。")
    ApiResponse<CheckItemTemplateApplicationService.ImportResult> importWorkbook(@RequestPart("file") MultipartFile file,
                                                                                  HttpServletRequest servletRequest) throws IOException {
        return ApiResponse.ok(templateApplicationService.importWorkbook(file.getBytes(), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping
    @Operation(summary = "修改检查项", description = "唯一修改入口。itemId 指向大类时可同时传 items 维护其子项；指向小类时仅传 itemId、itemName，不能传 items。")
    ApiResponse<CheckItemTemplateApplicationService.TemplateUpdateView> update(@Valid @RequestBody UpdateTemplateRequest request,
                                                                           HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.update(new CheckItemTemplateApplicationService.UpdateTemplateCommand(
                request.itemId(), request.itemName(), (request.items() == null ? List.<UpdateTemplateItemRequest>of() : request.items()).stream()
                        .map(item -> new CheckItemTemplateApplicationService.UpdateItemCommand(item.itemId(), item.itemName())).toList()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @DeleteMapping("/{checkItemId}")
    @Operation(summary = "删除检查项大类或小类", description = "必须同时传检查项 ID 与 category。category=CATEGORY 时传大类ID，并逻辑停用该大类及本评审类型下全部子项；category=ITEM 时仅接受小类 ID，只逻辑停用该小类。不会物理删除历史定义。")
    ApiResponse<Void> disable(
            @Parameter(description = "待删除检查项记录 ID", required = true) @PathVariable("checkItemId") long checkItemId,
            @Parameter(description = "删除目标类别：CATEGORY=大类，ITEM=小类", required = true)
            @RequestParam("category") CheckItemTemplateApplicationService.DeleteTargetCategory category,
            HttpServletRequest servletRequest) {
        templateApplicationService.disable(checkItemId, category, CurrentUserHolder.require());
        return ApiResponse.ok(null, traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "新增检查项类别请求")
    record CreateTemplateRequest(@Schema(description = "适用评审类型", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewType reviewType,
                                 @Schema(description = "检查项大类名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String categoryName,
                                 @Schema(description = "该大类包含的检查项子类；可不传或传空数组，提交顺序即展示顺序") List<@Valid TemplateItemRequest> items) {
    }

    @Schema(description = "统一修改检查项请求")
    record UpdateTemplateRequest(@Schema(description = "待修改的大类或小类 ID", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long itemId,
                                 @Schema(description = "修改后的名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemName,
                                 @Schema(description = "仅修改大类时可传：完整子项列表；已有子项传 itemId，新增子项不传") List<@Valid UpdateTemplateItemRequest> items) {
    }
    @Schema(description = "检查项子类内容")
    record TemplateItemRequest(@Schema(description = "检查项名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemName) { }
    @Schema(description = "修改检查项子类内容")
    record UpdateTemplateItemRequest(@Schema(description = "已有子检查项 ID；新增子检查项不传") Long itemId,
                                     @Schema(description = "检查项名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemName) { }
}
