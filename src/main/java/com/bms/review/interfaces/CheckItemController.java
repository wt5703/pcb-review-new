package com.bms.review.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
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
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestHeader;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.net.URLDecoder.decode;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 提供任务内互检固定检查项的查询和意见提交接口
 */
@RestController
@Validated
@RequestMapping("/tasks/{taskId}/check-items")
@Tag(name = "任务互检检查项", description = "查询任务内固定互检检查项，填写互检单意见。")
public class CheckItemController {
    private final TaskCheckItemApplicationService taskCheckItemApplicationService;

    public CheckItemController(TaskCheckItemApplicationService taskCheckItemApplicationService) {
        this.taskCheckItemApplicationService = taskCheckItemApplicationService;
    }

    @GetMapping
    @Operation(summary = "查询任务互检单详情", description = "任务的互检单详情")
    ApiResponse<List<TaskCheckItemApplicationService.CheckItemCategoryView>> list(@PathVariable long taskId,
                                                                                    @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.list(taskId, employeeNo, null), traceId(servletRequest));
    }

    @PutMapping("/{itemId}")
    @Operation(summary = "提交互检检查项结论", description = "comment 是用户填写的文字意见，richText 是富文本信息。FAIL（不合格）必须填写 comment；PASS（合格）和 NC 均可不填写 comment、richText。")
    ApiResponse<TaskCheckItemApplicationService.CheckItemView> submit(@PathVariable long taskId, @PathVariable long itemId,
                                                                        @Valid @RequestBody SubmitCheckItemRequest request,
                                                                        @NotBlank @RequestHeader("userId") String employeeNo, @NotBlank @RequestHeader("userName") String userName, HttpServletRequest servletRequest) {
        userName = decode(userName, StandardCharsets.UTF_8);
        return ApiResponse.ok(taskCheckItemApplicationService.submit(taskId, itemId,
                new TaskCheckItemApplicationService.SubmitCheckItemCommand(request.result(), request.comment(), request.richText()),
                employeeNo, userName), traceId(servletRequest));
    }

    @PutMapping("/batch")
    @Operation(summary = "批量提交整张互检单", description = "每项仅接收 itemId、result、comment、richText。FAIL（不合格）必须填写 comment；PASS（合格）和 NC 均可不填写 comment、richText。")
    ApiResponse<List<TaskCheckItemApplicationService.CheckItemView>> submitBatch(@PathVariable long taskId,
                                                                                   @Valid @RequestBody SubmitCheckItemsRequest request,
                                                                                   @NotBlank @RequestHeader("userId") String employeeNo, @NotBlank @RequestHeader("userName") String userName, HttpServletRequest servletRequest) {
        userName = decode(userName, StandardCharsets.UTF_8);
        return ApiResponse.ok(taskCheckItemApplicationService.submitBatch(taskId, request.items().stream()
                .map(item -> new TaskCheckItemApplicationService.SubmitBatchItemCommand(item.itemId(), item.result(), item.comment(), item.richText())).toList(),
                employeeNo, userName), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "提交检查项结论请求")
    record SubmitCheckItemRequest(@Schema(description = "检查结论：PASS 合格、FAIL 不合格、NC", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull CheckResult result,
                                  @Schema(description = "用户填写的文字意见；仅 FAIL 时必填，NC 可为空") String comment,
                                  @Schema(description = "富文本补充说明；支持文字与内嵌 data URI 图片") String richText) {
    }
    @Schema(description = "整张互检单批量提交请求")
    record SubmitCheckItemsRequest(@Schema(description = "待提交的检查项结果列表，至少一项", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<@Valid BatchCheckItemRequest> items) { }
    @Schema(description = "互检单中的单条检查项结果")
    record BatchCheckItemRequest(@Schema(description = "任务检查项实例 ID", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long itemId,
                                 @Schema(description = "检查结果：PASS 合格、FAIL 不合格、NC", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull CheckResult result,
                                 @Schema(description = "用户填写的文字意见；仅 FAIL 时必填，NC 可为空") String comment,
                                 @Schema(description = "富文本补充说明，可包含文字和内嵌 data URI 图片；后端以字符串保存并回显") String richText) { }
}
