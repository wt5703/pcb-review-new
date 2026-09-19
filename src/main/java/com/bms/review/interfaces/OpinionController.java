package com.bms.review.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.application.OpinionApplicationService;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.domain.ReplyType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 对外提供统一评审意见、设计者答复、提出人确认和撤回接口；设计版本重新上传后只需通过对应意见答复再次确认，不会重启完整评审。
 */
@RestController
public class OpinionController {
    private final OpinionApplicationService opinionApplicationService;

    public OpinionController(OpinionApplicationService opinionApplicationService) {
        this.opinionApplicationService = opinionApplicationService;
    }

    @PostMapping("/tasks/{taskId}/opinions")
    ApiResponse<OpinionApplicationService.OpinionView> raise(@PathVariable long taskId, @Valid @RequestBody RaiseOpinionRequest request,
                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.raise(new OpinionApplicationService.RaiseOpinionCommand(taskId, request.sourceType(),
                request.sourceItemId(), request.content(), request.fileVersionId()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/tasks/{taskId}/opinions")
    ApiResponse<List<OpinionApplicationService.OpinionView>> list(@PathVariable long taskId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.list(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @GetMapping("/tasks/{taskId}/opinions/summary")
    ApiResponse<OpinionApplicationService.OpinionSummary> summary(@PathVariable long taskId, HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.summary(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/opinions/{opinionId}/replies")
    ApiResponse<OpinionApplicationService.OpinionView> reply(@PathVariable long opinionId, @Valid @RequestBody ReplyOpinionRequest request,
                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.reply(opinionId, new OpinionApplicationService.ReplyOpinionCommand(
                request.replyType(), request.reason(), request.fileVersionId()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/opinions/{opinionId}/confirmations")
    ApiResponse<OpinionApplicationService.OpinionView> confirm(@PathVariable long opinionId,
                                                                 @Valid @RequestBody ConfirmOpinionRequest request,
                                                                 HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.confirm(opinionId, new OpinionApplicationService.ConfirmOpinionCommand(
                request.passed(), request.comment()), CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/opinions/{opinionId}/withdraw")
    ApiResponse<OpinionApplicationService.OpinionView> withdraw(@PathVariable long opinionId,
                                                                  @Valid @RequestBody WithdrawOpinionRequest request,
                                                                  HttpServletRequest servletRequest) {
        return ApiResponse.ok(opinionApplicationService.withdraw(opinionId, new OpinionApplicationService.WithdrawOpinionCommand(request.reason()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    record RaiseOpinionRequest(@NotNull OpinionSourceType sourceType, Long sourceItemId, @NotBlank String content, Long fileVersionId) {
    }
    record ReplyOpinionRequest(@NotNull ReplyType replyType, String reason, Long fileVersionId) {
    }
    record ConfirmOpinionRequest(boolean passed, String comment) {
    }
    record WithdrawOpinionRequest(@NotBlank String reason) {
    }
}
