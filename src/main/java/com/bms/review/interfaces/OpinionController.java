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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 对外提供统一评审意见、设计者答复、提出人确认和撤回接口；设计版本重新上传后只需通过对应意见答复再次确认，不会重启完整评审。
 */
@RestController
@Tag(name = "评审意见", description = "管理专家意见、设计者答复和意见确认闭环；意见可按问题等级和状态筛选。")
public class OpinionController {
    private final OpinionApplicationService opinionApplicationService;

    public OpinionController(OpinionApplicationService opinionApplicationService) {
        this.opinionApplicationService = opinionApplicationService;
    }

    @PostMapping("/tasks/{taskId}/opinions")
    @Operation(summary = "提出评审意见", description = "在当前任务节点提交一条具体意见，可关联问题等级、设计文件版本及多张已登记的意见图片；图片先使用 OPINION_ATTACHMENT 类别上传，再传入图片文件 ID。")
    ApiResponse<OpinionApplicationService.OpinionView> raise(@PathVariable long taskId, @Valid @RequestBody RaiseOpinionRequest request,
                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.raise(new OpinionApplicationService.RaiseOpinionCommand(taskId, request.sourceType(),
                request.sourceItemId(), request.content(), request.fileVersionId(), request.severity(), request.imageUrl(), request.attachmentFileIds()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/tasks/{taskId}/opinions")
    @Operation(summary = "查询任务意见列表", description = "返回意见、设计者答复和确认记录。severity 可按 SERIOUS、GENERAL、MINOR 筛选，status 可按意见闭环状态筛选。")
    ApiResponse<List<OpinionApplicationService.OpinionView>> list(@PathVariable long taskId,
            @RequestParam(required = false) @Parameter(description = "问题等级：SERIOUS 严重、GENERAL 一般、MINOR 轻微") String severity,
            @RequestParam(required = false) @Parameter(description = "意见状态：PENDING_REPLY、PENDING_CONFIRMATION、CONFIRMED_PASS、CONFIRMED_REJECTED、WITHDRAWN") OpinionStatus status,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.list(taskId, severity, status, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/tasks/{taskId}/opinions/summary")
    @Operation(summary = "查询设计者答复页专家意见汇总", description = "除各状态数量外，返回尚未确认的意见及其专家，以及尚未提交处理结果的专家。")
    ApiResponse<OpinionApplicationService.OpinionSummary> summary(@PathVariable long taskId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.summary(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping({"/opinions/{opinionId}/reply", "/opinions/{opinionId}/replies"})
    @Operation(summary = "设计者答复专家意见", description = "推荐路径为 /opinions/{opinionId}/reply；设计者可对指定意见提交答复，答复返回 opinionId，便于前端与原意见一一对应。旧 replies 路径暂时兼容。")
    ApiResponse<OpinionApplicationService.OpinionView> reply(@PathVariable long opinionId, @Valid @RequestBody ReplyOpinionRequest request,
                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.reply(opinionId, new OpinionApplicationService.ReplyOpinionCommand(
                request.replyType(), request.reason(), request.fileVersionId()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping({"/opinions/{opinionId}/confirm", "/opinions/{opinionId}/confirmations"})
    @Operation(summary = "专家确认设计者答复", description = "推荐路径为 /opinions/{opinionId}/confirm；意见提出人确认答复通过或不通过，不通过后设计者可重新答复。旧 confirmations 路径暂时兼容。")
    ApiResponse<OpinionApplicationService.OpinionView> confirm(@PathVariable long opinionId,
                                                                 @Valid @RequestBody ConfirmOpinionRequest request,
                                                                 HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.confirm(opinionId, new OpinionApplicationService.ConfirmOpinionCommand(
                request.passed(), request.comment()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/opinions/{opinionId}/withdraw")
    @Operation(summary = "撤回评审意见", description = "仅意见提出人可撤回未关闭意见，必须填写撤回原因。")
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
    record RaiseOpinionRequest(@Schema(description = "意见来源类型", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull OpinionSourceType sourceType,
                               @Schema(description = "来源检查项 ID；固定互检检查项意见时必填") Long sourceItemId,
                               @Schema(description = "具体、可执行的评审意见内容", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String content,
                               @Schema(description = "关联的设计文件版本 ID") Long fileVersionId,
                               @Schema(description = "问题等级：SERIOUS 严重、GENERAL 一般、MINOR 轻微") String severity,
                               @Schema(description = "兼容历史调用的单张图片 URL；新调用请使用 attachmentFileIds") String imageUrl,
                               @Schema(description = "专家意见图片对应的文件 ID 列表；文件须先以 OPINION_ATTACHMENT 类别登记，支持多张图片并按传入顺序展示") List<Long> attachmentFileIds) {
    }
    @Schema(description = "设计者答复意见请求")
    record ReplyOpinionRequest(@Schema(description = "答复结论", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReplyType replyType,
                               @Schema(description = "答复说明或整改说明") String reason, @Schema(description = "重新上传后关联的文件版本 ID") Long fileVersionId) {
    }
    @Schema(description = "专家确认答复请求")
    record ConfirmOpinionRequest(@Schema(description = "是否确认通过", requiredMode = Schema.RequiredMode.REQUIRED) boolean passed, @Schema(description = "确认意见") String comment) {
    }
    @Schema(description = "撤回意见请求")
    record WithdrawOpinionRequest(@Schema(description = "撤回原因", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String reason) {
    }
}
