package com.leapmotor.pcbreview.review.interfaces;

import com.leapmotor.pcbreview.common.ApiResponse;
import com.leapmotor.pcbreview.common.TraceIdFilter;
import com.leapmotor.pcbreview.identity.application.CurrentUserHolder;
import com.leapmotor.pcbreview.review.application.TaskCheckItemApplicationService;
import com.leapmotor.pcbreview.review.domain.CheckResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
 * @description 提供任务内互检固定检查项的查询和提交接口；提交采用乐观锁版本号，防止两位互检人员覆盖彼此的检查结论。
 */
@RestController
@RequestMapping("/tasks/{taskId}/check-items")
public class CheckItemController {
    private final TaskCheckItemApplicationService taskCheckItemApplicationService;

    public CheckItemController(TaskCheckItemApplicationService taskCheckItemApplicationService) {
        this.taskCheckItemApplicationService = taskCheckItemApplicationService;
    }

    @GetMapping
    ApiResponse<List<TaskCheckItemApplicationService.CheckItemView>> list(@PathVariable long taskId,
                                                                            HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.list(taskId, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PutMapping("/{itemId}")
    ApiResponse<TaskCheckItemApplicationService.CheckItemView> submit(@PathVariable long taskId, @PathVariable long itemId,
                                                                        @Valid @RequestBody SubmitCheckItemRequest request,
                                                                        HttpServletRequest servletRequest) {
        return ApiResponse.ok(taskCheckItemApplicationService.submit(taskId, itemId,
                new TaskCheckItemApplicationService.SubmitCheckItemCommand(request.result(), request.comment(), request.linkedOpinionId(), request.version()),
                CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    record SubmitCheckItemRequest(@NotNull CheckResult result, String comment, Long linkedOpinionId,
                                  @PositiveOrZero long version) {
    }
}
