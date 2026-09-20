package com.bms.review.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.application.TaskCheckItemApplicationService;
import com.bms.review.domain.CheckResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 提供任务内互检固定检查项的查询和提交接口；提交采用乐观锁版本号，防止两位互检人员覆盖彼此的检查结论。
 */
@RestController
@RequestMapping("/tasks/{taskId}/check-items")
@Tag(name = "任务互检检查项", description = "查询、填写任务内固定互检检查项，并关联不合格项的评审意见和附件。")
public class CheckItemController {
    private final TaskCheckItemApplicationService taskCheckItemApplicationService;

    public CheckItemController(TaskCheckItemApplicationService taskCheckItemApplicationService) {
        this.taskCheckItemApplicationService = taskCheckItemApplicationService;
    }

    @GetMapping
    @Operation(summary = "查询任务互检检查项", description = "读取当前任务按模板生成的检查项实例及处理结果。")
    ApiResponse<List<TaskCheckItemApplicationService.CheckItemView>> list(@PathVariable long taskId,
                                                                            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.list(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping("/{itemId}")
    @Operation(summary = "提交互检检查项结论", description = "提交通过、不通过或不适用结论。不通过时必须关联同任务、同检查项来源的评审意见。")
    ApiResponse<TaskCheckItemApplicationService.CheckItemView> submit(@PathVariable long taskId, @PathVariable long itemId,
                                                                        @Valid @RequestBody SubmitCheckItemRequest request,
                                                                        HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.submit(taskId, itemId,
                new TaskCheckItemApplicationService.SubmitCheckItemCommand(request.result(), request.comment(), request.linkedOpinionId(), request.version()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping("/batch")
    @Operation(summary = "批量提交整张互检单", description = "一次提交多个检查项。FAIL 时检查意见和问题图片必填，后端自动创建或更新同源互检意见并关联图片；任意一项失败则整单回滚。")
    ApiResponse<List<TaskCheckItemApplicationService.CheckItemView>> submitBatch(@PathVariable long taskId,
                                                                                   @Valid @RequestBody SubmitCheckItemsRequest request,
                                                                                   HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.submitBatch(taskId, request.items().stream()
                .map(item -> new TaskCheckItemApplicationService.SubmitBatchItemCommand(item.itemId(), item.result(), item.comment(), item.attachmentFileIds(), item.version())).toList(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/{itemId}/attachments")
    @Operation(summary = "关联检查项附件", description = "将已登记文件作为指定检查项的佐证附件，并按排序号展示。")
    ApiResponse<Void> attachFile(@PathVariable long taskId, @PathVariable long itemId,
                                 @Valid @RequestBody AttachFileRequest request, HttpServletRequest servletRequest) {
        taskCheckItemApplicationService.attachFile(taskId, itemId, request.fileId(), request.sortNo(), CurrentUserHolder.require());
        return ApiResponse.ok(null, traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "提交检查项结论请求")
    record SubmitCheckItemRequest(@Schema(description = "检查结论：PASS 通过、FAIL 不通过、NOT_APPLICABLE 不适用", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull CheckResult result,
                                  @Schema(description = "检查说明") String comment, @Schema(description = "不通过时关联的同源评审意见 ID") Long linkedOpinionId,
                                  @Schema(description = "当前检查项乐观锁版本号", requiredMode = Schema.RequiredMode.REQUIRED) @PositiveOrZero long version) {
    }
    @Schema(description = "关联检查项附件请求")
    record AttachFileRequest(@Schema(description = "已登记的文件 ID", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long fileId,
                             @Schema(description = "附件显示排序号") @PositiveOrZero int sortNo) {
    }
    @Schema(description = "整张互检单批量提交请求")
    record SubmitCheckItemsRequest(@Schema(description = "待提交的检查项结果列表，至少一项", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<@Valid BatchCheckItemRequest> items) { }
    @Schema(description = "互检单中的单条检查项结果")
    record BatchCheckItemRequest(@Schema(description = "任务检查项实例 ID", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long itemId,
                                 @Schema(description = "检查结果：PASS 合格、FAIL 不合格、NOT_APPLICABLE 不适用", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull CheckResult result,
                                 @Schema(description = "检查意见。FAIL 或 NOT_APPLICABLE 时必填；FAIL 会作为自动创建的互检意见内容") String comment,
                                 @Schema(description = "已登记的问题图片文件 ID 列表。FAIL 时至少一张，顺序即图片展示顺序") List<Long> attachmentFileIds,
                                 @Schema(description = "检查项当前乐观锁版本号", requiredMode = Schema.RequiredMode.REQUIRED) @PositiveOrZero long version) { }
}
