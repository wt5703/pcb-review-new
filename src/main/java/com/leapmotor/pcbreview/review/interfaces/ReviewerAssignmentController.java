package com.leapmotor.pcbreview.review.interfaces;

import com.leapmotor.pcbreview.common.ApiResponse;
import com.leapmotor.pcbreview.common.TraceIdFilter;
import com.leapmotor.pcbreview.identity.application.CurrentUserHolder;
import com.leapmotor.pcbreview.review.application.ReviewerAssignmentService;
import com.leapmotor.pcbreview.review.domain.ReviewRole;
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
public class ReviewerAssignmentController {
    private final ReviewerAssignmentService reviewerAssignmentService;

    public ReviewerAssignmentController(ReviewerAssignmentService reviewerAssignmentService) {
        this.reviewerAssignmentService = reviewerAssignmentService;
    }

    @PostMapping
    ApiResponse<List<ReviewerAssignmentService.ReviewerView>> assign(@PathVariable long taskId,
                                                                       @Valid @RequestBody AssignReviewersRequest request,
                                                                       HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewerAssignmentService.assign(taskId, request.role(), request.reviewerIds(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping
    ApiResponse<List<ReviewerAssignmentService.ReviewerView>> reassign(@PathVariable long taskId,
                                                                         @Valid @RequestBody AssignReviewersRequest request,
                                                                         HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewerAssignmentService.reassign(taskId, request.role(), request.reviewerIds(),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping("/me/no-opinion")
    ApiResponse<Void> submitNoOpinion(@PathVariable long taskId, HttpServletRequest servletRequest) {
        reviewerAssignmentService.submitNoOpinion(taskId, CurrentUserHolder.require());
        return ApiResponse.ok(null, traceId(servletRequest));
    }

    @GetMapping
    ApiResponse<List<ReviewerAssignmentService.ReviewerView>> list(@PathVariable long taskId,
                                                                     @RequestParam @NotNull ReviewRole role,
                                                                     HttpServletRequest servletRequest) {
        return ApiResponse.ok(reviewerAssignmentService.listActive(taskId, role), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    record AssignReviewersRequest(@NotNull ReviewRole role, @NotEmpty List<Long> reviewerIds) {
    }
}
