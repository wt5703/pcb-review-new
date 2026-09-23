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
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 提供任务内互检固定检查项的查询和意见提交接口
 */
@RestController
@RequestMapping("/tasks/{taskId}/check-items")
@Tag(name = "任务互检检查项", description = "查询任务内固定互检检查项，填写互检单意见。")
public class CheckItemController {
    private final TaskCheckItemApplicationService taskCheckItemApplicationService;

    public CheckItemController(TaskCheckItemApplicationService taskCheckItemApplicationService) {
        this.taskCheckItemApplicationService = taskCheckItemApplicationService;
    }

    @GetMapping
    @Operation(summary = "查询任务互检检查项", description = "返回 [{ category, items }] 分组结构：category 是检查大类，items 是其子检查项。items 仅返回 id、itemName、sortNo 和可空 opinion；检查结论、文字意见和富文本只在 opinion.result、opinion.comment、opinion.richText 中返回。opinion 不返回 content、status；接口不返回模板内部编码、version 等字段。")
    ApiResponse<List<TaskCheckItemApplicationService.CheckItemCategoryView>> list(@PathVariable long taskId,
                                                                                    HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.list(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping("/{itemId}")
    @Operation(summary = "提交互检检查项结论", description = "comment 是用户填写的文字意见，richText 是富文本信息，result 可传 PASS（合格）、FAIL（不合格）或 NC")
    ApiResponse<TaskCheckItemApplicationService.CheckItemView> submit(@PathVariable long taskId, @PathVariable long itemId,
                                                                        @Valid @RequestBody SubmitCheckItemRequest request,
                                                                        HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.submit(taskId, itemId,
                new TaskCheckItemApplicationService.SubmitCheckItemCommand(request.result(), request.comment(), request.richText()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping("/batch")
    @Operation(summary = "批量提交整张互检单", description = "每项仅接收 itemId、result、comment、richText。comment 是用户填写的文字意见，richText 是富文本信息，result 可传 PASS（合格）、FAIL（不合格）或 NC")
    ApiResponse<List<TaskCheckItemApplicationService.CheckItemView>> submitBatch(@PathVariable long taskId,
                                                                                   @Valid @RequestBody SubmitCheckItemsRequest request,
                                                                                   HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.submitBatch(taskId, request.items().stream()
                .map(item -> new TaskCheckItemApplicationService.SubmitBatchItemCommand(item.itemId(), item.result(), item.comment(), item.richText())).toList(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "提交检查项结论请求")
    record SubmitCheckItemRequest(@Schema(description = "检查结论：PASS 合格、FAIL 不合格、NC", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull CheckResult result,
                                  @Schema(description = "用户填写的文字意见；FAIL 或 NC 时必填") String comment,
                                  @Schema(description = "富文本补充说明；支持文字与内嵌 data URI 图片") String richText) {
    }
    @Schema(description = "整张互检单批量提交请求")
    record SubmitCheckItemsRequest(@Schema(description = "待提交的检查项结果列表，至少一项", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<@Valid BatchCheckItemRequest> items) { }
    @Schema(description = "互检单中的单条检查项结果")
    record BatchCheckItemRequest(@Schema(description = "任务检查项实例 ID", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long itemId,
                                 @Schema(description = "检查结果：PASS 合格、FAIL 不合格、NC", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull CheckResult result,
                                 @Schema(description = "用户填写的文字意见；FAIL 或 NC 时必填") String comment,
                                 @Schema(description = "富文本补充说明，可包含文字和内嵌 data URI 图片；后端以字符串保存并回显") String richText) { }
}
