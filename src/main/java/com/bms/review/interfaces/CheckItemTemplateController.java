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
 * @description 互检单模板管理，会同步更新到任务的互检单
 */
@RestController
@RequestMapping("/check-item-templates")
@Tag(name = "互检检查项模板", description = "按“检查项大类—检查项子类”一对多结构维护互检模板，并支持 Excel 批量导入。")
public class CheckItemTemplateController {
    private final CheckItemTemplateApplicationService templateApplicationService;

    public CheckItemTemplateController(CheckItemTemplateApplicationService templateApplicationService) {
        this.templateApplicationService = templateApplicationService;
    }

    @GetMapping("/existence")
    @Operation(summary = "检查互检单模板是否存在", description = "导入 Excel 前调用。exists=true 时，页面须经用户确认后才可在导入接口传 confirmed=true 替换旧模板。")
    ApiResponse<CheckItemTemplateApplicationService.TemplateExistenceView> checkExistence(
            @Parameter(description = "模板所属评审类型：PCB 或 SCHEMATIC", required = true) @RequestParam @NotNull ReviewType reviewType,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.checkExistence(reviewType, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @Operation(summary = "Excel 批量导入互检检查项模板", description = "导入前先调用 existence 接口。存在旧模板时，只有 confirmed=true 才会逻辑停用旧模板并导入新模板；未确认会返回提示，不会改变旧模板。")
    ApiResponse<CheckItemTemplateApplicationService.ImportResult> importWorkbook(@RequestPart("file") MultipartFile file,
                                                                                 @RequestParam("reviewType") @NotNull ReviewType reviewType,
                                                                                 @RequestParam(value = "confirmed", defaultValue = "false") boolean confirmed,
                                                                                 HttpServletRequest servletRequest) throws IOException {
        return ApiResponse.ok(templateApplicationService.importWorkbook(file.getBytes(), reviewType, confirmed,
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping
    @Operation(summary = "查询互检检查项模板", description = "返回原理图或PCB评审的互检单模板")
    ApiResponse<CheckItemTemplateApplicationService.TemplateListView> get(
            @Parameter(description = "模板所属评审类型：PCB 或 SCHEMATIC", required = true) @RequestParam @NotNull ReviewType reviewType,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.get(reviewType, CurrentUserHolder.require()), traceId(servletRequest));
    }


    @PostMapping
    @Operation(summary = "新增检查项大类及子检查项", description = "一次新增一个检查项大类及其可选的子检查项。categoryName 必填")
    ApiResponse<CheckItemTemplateApplicationService.TemplateCategoryView> create(@Valid @RequestBody CreateTemplateRequest request,
                                                                                 HttpServletRequest servletRequest) {

        CheckItemTemplateApplicationService.CreateCategoryCommand command = toCreateCategoryCommand(request);

        CheckItemTemplateApplicationService.TemplateCategoryView created = templateApplicationService.create(
                command, CurrentUserHolder.require());

        return ApiResponse.ok(created, traceId(servletRequest));
    }


    @PutMapping
    @Operation(summary = "修改检查项", description = "itemId 指向大类时可同时传 items 维护其子项；指向小类时仅传 itemId、itemName")
    ApiResponse<CheckItemTemplateApplicationService.TemplateUpdateView> update(@Valid @RequestBody UpdateTemplateRequest request,
                                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(templateApplicationService.update(new CheckItemTemplateApplicationService.UpdateTemplateCommand(
                        request.itemId(), request.itemName(), (request.items() == null ? List.<UpdateTemplateItemRequest>of() : request.items()).stream()
                        .map(item -> new CheckItemTemplateApplicationService.UpdateItemCommand(item.itemId(), item.itemName())).toList()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @DeleteMapping("/{checkItemId}")
    @Operation(summary = "删除检查项大类或小类", description = "必须同时传检查项 ID 与 category。category=CATEGORY 时传大类 ID，逻辑停用该大类及其全部子项，category=ITEM 时仅接受小类 ID，逻辑停用该小类")
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

    private CheckItemTemplateApplicationService.CreateCategoryCommand toCreateCategoryCommand(CreateTemplateRequest request) {
        return new CheckItemTemplateApplicationService.CreateCategoryCommand(
                request.reviewType(),
                request.categoryName(),
                toCreateItemCommands(request.items()));
    }

    private List<CheckItemTemplateApplicationService.CreateItemCommand> toCreateItemCommands(List<TemplateItemRequest> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(item -> new CheckItemTemplateApplicationService.CreateItemCommand(item.itemName()))
                .toList();
    }

    @Schema(description = "新增检查项类别请求")
    record CreateTemplateRequest(
            @Schema(description = "适用评审类型", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewType reviewType,
            @Schema(description = "检查项大类名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String categoryName,
            @Schema(description = "该大类包含的检查项子类；可不传或传空数组，提交顺序即展示顺序") List<@Valid TemplateItemRequest> items) {
    }

    @Schema(description = "统一修改检查项请求")
    record UpdateTemplateRequest(
            @Schema(description = "待修改的大类或小类 ID", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long itemId,
            @Schema(description = "修改后的名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemName,
            @Schema(description = "仅修改大类时可传：完整子项列表；已有子项传 itemId，新增子项不传") List<@Valid UpdateTemplateItemRequest> items) {
    }

    @Schema(description = "检查项子类内容")
    record TemplateItemRequest(
            @Schema(description = "检查项名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemName) {
    }

    @Schema(description = "修改检查项子类内容")
    record UpdateTemplateItemRequest(@Schema(description = "已有子检查项 ID；新增子检查项不传") Long itemId,
                                     @Schema(description = "检查项名称", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String itemName) {
    }
}
