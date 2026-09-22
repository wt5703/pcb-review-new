package com.bms.review.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.application.OpinionApplicationService;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.domain.ReplyType;
import com.bms.review.domain.OpinionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 对外提供统一评审意见、设计者答复、提出人确认和撤回接口；设计版本重新上传后只需通过对应意见答复再次确认，不会重启完整评审。
 */
@RestController
@Validated
@Tag(name = "评审意见", description = "管理专家意见、设计者答复和意见确认闭环；意见可按问题等级和状态筛选。")
public class OpinionController {
    private final OpinionApplicationService opinionApplicationService;

    public OpinionController(OpinionApplicationService opinionApplicationService) {
        this.opinionApplicationService = opinionApplicationService;
    }

    @PostMapping("/tasks/{taskId}/opinions")
    @Operation(summary = "提出评审意见", description = "在当前任务节点提交一条具体意见。richText 为前端编辑器生成的完整富文本字符串（可内嵌图片），后端按字符串原样保存并随意见列表回显；content 保留为兼容字段。接口不接收图片 URL、附件文件 ID 或独立图片上传参数。")
    ApiResponse<OpinionApplicationService.OpinionView> raise(@PathVariable long taskId, @Valid @RequestBody RaiseOpinionRequest request,
                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.raise(new OpinionApplicationService.RaiseOpinionCommand(taskId, request.sourceType(),
                request.sourceItemId(), request.content(), request.richText(), request.severity()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/tasks/{taskId}/opinions")
    @Operation(summary = "分页查询任务意见列表", description = "每条记录在同一个扁平模型中返回专家意见、冗余的提出人姓名 raisedByName、全部设计者答复及各答复的确认结果，默认按意见提出时间倒序。severity 可按 SERIOUS、GENERAL、MINOR 筛选；sourceType 可按专家、工艺或结构来源筛选；scene=REVIEW_WORKSPACE 仅返回当前登录专家提出的意见，scene=DESIGNER_REPLY 返回任务所有意见。pageNo 从 1 开始，pageSize 最大为 100。")
    ApiResponse<OpinionApplicationService.OpinionPage> list(@PathVariable long taskId,
            @RequestParam(required = false) @Parameter(description = "问题等级：SERIOUS 严重、GENERAL 一般、MINOR 轻微") String severity,
            @RequestParam(required = false) @Parameter(description = "意见状态：PENDING_REPLY  待答复、PENDING_CONFIRMATION 待确认、CONFIRMED_PASS 确认通过、CONFIRMED_REJECTED 确认不通过、WITHDRAWN 撤回") OpinionStatus status,
            @RequestParam(required = false) @Parameter(description = "意见来源：EXPERT_REVIEW=专家评审、PROCESS_REVIEW=工艺评审、STRUCTURE_REVIEW=结构评审  PCB_MUTUAL_CHECK=PCB互检单 SCHEMATIC_MUTUAL_CHECK=原理图互检单") OpinionSourceType sourceType,
            @RequestParam(required = false, defaultValue = "DESIGNER_REPLY") @Parameter(description = "查询场景：REVIEW_WORKSPACE 仅当前登录专家提出的意见；DESIGNER_REPLY 展示任务全部意见") String scene,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码，从 1 开始") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页条数，最大 100") @Min(1) @Max(100) int pageSize,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.listPage(taskId, severity, status, sourceType, scene, pageNo, pageSize,
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/tasks/{taskId}/opinions/summary")
    @Operation(summary = "查询设计者答复页专家意见汇总", description = "设计者答复页面，专家意见汇总")
    ApiResponse<OpinionApplicationService.OpinionSummary> summary(@PathVariable long taskId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.summary(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/opinions/{opinionId}/reply")
    @Operation(summary = "设计者答复专家意见", description = "设计者答复专家提出的意见")
    ApiResponse<OpinionApplicationService.OpinionView> reply(@PathVariable long opinionId, @Valid @RequestBody ReplyOpinionRequest request,
                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.reply(opinionId, new OpinionApplicationService.ReplyOpinionCommand(
                request.replyType(), request.reason()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/opinions/{opinionId}/confirm")
    @Operation(summary = "专家确认设计者答复", description = "意见提出人确认答复通过或不通过，不通过后设计者可重新答复")
    ApiResponse<OpinionApplicationService.OpinionView> confirm(@PathVariable long opinionId,
                                                                 @Valid @RequestBody ConfirmOpinionRequest request,
                                                                 HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.confirm(opinionId, new OpinionApplicationService.ConfirmOpinionCommand(
                request.passed(), request.comment()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/opinions/{opinionId}/withdraw")
    @Operation(summary = "撤回评审意见", description = "仅意见提出人可撤回未关闭意见，必须填写撤回原因")
    ApiResponse<OpinionApplicationService.OpinionView> withdraw(@PathVariable long opinionId,
                                                                  @Valid @RequestBody WithdrawOpinionRequest request,
                                                                  HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.withdraw(opinionId, new OpinionApplicationService.WithdrawOpinionCommand(request.reason()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "提出评审意见请求")
    record RaiseOpinionRequest(@Schema(description = "意见来源：EXPERT_REVIEW 专家评审、PROCESS_REVIEW 工艺评审、STRUCTURE_REVIEW 结构评审  PCB_MUTUAL_CHECK  PCB互检单 SCHEMATIC_MUTUAL_CHECK 原理图互检单", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull OpinionSourceType sourceType,
                               @Schema(description = "来源检查项 ID；固定互检检查项意见时必填") Long sourceItemId,
                               @Schema(description = "具体、可执行的评审意见内容", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String content,
                               @Schema(description = "富文本提取意见，支持文字与内嵌 data URI 图片；为空时使用 content", requiredMode = Schema.RequiredMode.NOT_REQUIRED) String richText,
                               @Schema(description = "问题等级：SERIOUS 严重、GENERAL 一般、MINOR 轻微") String severity) {
    }
    @Schema(description = "设计者答复意见请求")
    record ReplyOpinionRequest(@Schema(description = "答复结论", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReplyType replyType,
                               @Schema(description = "答复说明或整改说明；不支持上传文件或粘贴图片") String reason) {
    }
    @Schema(description = "专家确认答复请求")
    record ConfirmOpinionRequest(@Schema(description = "是否确认通过", requiredMode = Schema.RequiredMode.REQUIRED) boolean passed, @Schema(description = "确认意见") String comment) {
    }
    @Schema(description = "撤回意见请求")
    record WithdrawOpinionRequest(@Schema(description = "撤回原因", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String reason) {
    }
}
