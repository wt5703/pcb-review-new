package com.bms.review.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.application.ReviewerAssignmentService;
import com.bms.review.domain.ReviewRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 提供评审人员和互检人员的分配、改派、当前人员无意见提交及分配结果查询接口；任务结束和意见闭环由后续领域接口处理。
 */
@RestController
@RequestMapping("/tasks/{taskId}/reviewers")
@Tag(name = "评审人员", description = "维护任务在当前阶段的评审人员分配，并支持评审人员明确提交“无意见”。")
public class ReviewerAssignmentController {
    private final ReviewerAssignmentService reviewerAssignmentService;

    public ReviewerAssignmentController(ReviewerAssignmentService reviewerAssignmentService) {
        this.reviewerAssignmentService = reviewerAssignmentService;
    }

    @PostMapping
    @Operation(summary = "分配评审人员", description = "为指定评审职责首次分配一名或多名评审人员。同一职责下多人需要全部完成，角色才算完成。")
    ApiResponse<List<ReviewerAssignmentService.ReviewerView>> assign(@PathVariable long taskId,
                                                                       @Valid @RequestBody AssignReviewersRequest request,
                                                                       HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewerAssignmentService.assign(taskId, request.role(), request.reviewerIds(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping
    @Operation(summary = "改派评审人员", description = "取消指定职责当前未结束的人员分配，并按请求中的人员列表重新分配。")
    ApiResponse<List<ReviewerAssignmentService.ReviewerView>> reassign(@PathVariable long taskId,
                                                                         @Valid @RequestBody AssignReviewersRequest request,
                                                                         HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewerAssignmentService.reassign(taskId, request.role(), request.reviewerIds(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping({"/me/submit-no-opinion", "/me/no-opinion"})
    @Operation(summary = "提交本人无评审意见", description = "当前流程节点的评审人员明确提交“无意见”，用于多人评审完成条件计算。/me/no-opinion 保留为兼容旧调用方的废弃路径，请使用 /me/submit-no-opinion。")
    ApiResponse<Void> submitNoOpinion(@PathVariable long taskId, HttpServletRequest servletRequest) {
        reviewerAssignmentService.submitNoOpinion(taskId, CurrentUserHolder.require());
        return ApiResponse.ok(null, traceId(servletRequest));
    }

    @GetMapping
    @Operation(summary = "查询某评审角色的在途人员", description = "按任务和评审角色查询仍有效的人员分配记录。")
    ApiResponse<List<ReviewerAssignmentService.ReviewerView>> list(@PathVariable long taskId,
                                                                     @RequestParam @NotNull ReviewRole role,
                                                                     HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewerAssignmentService.listActive(taskId, role, CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "评审人员分配请求")
    record AssignReviewersRequest(@Schema(description = "需要分配的评审角色", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewRole role,
                                  @Schema(description = "评审人员用户 ID 列表，至少一名，支持多人", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty List<Long> reviewerIds) {
    }
}
