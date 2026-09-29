package com.bms.common.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.review.domain.ReviewRole;
import com.bms.review.domain.ReviewerWhitelistRole;
import com.bms.task.domain.TaskStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 向前端统一提供评审任务创建和筛选所需的固定字典值，避免页面硬编码 PCB 类型和评审角色枚举。
 */
@RestController
@RequestMapping("/dictionaries")
@Tag(name = "系统字典", description = "提供前端下拉框和多选框使用的固定业务字典。")
public class DictionaryController {
    private static final List<DictionaryItem> REVIEWER_WHITELIST_ROLES = List.of(
            new DictionaryItem(ReviewerWhitelistRole.HARDWARE_EXPERT.name(), ReviewerWhitelistRole.HARDWARE_EXPERT.displayName()),
            new DictionaryItem(ReviewerWhitelistRole.EMC_EXPERT.name(), ReviewerWhitelistRole.EMC_EXPERT.displayName()),
            new DictionaryItem(ReviewerWhitelistRole.STRUCTURE_EXPERT.name(), ReviewerWhitelistRole.STRUCTURE_EXPERT.displayName()),
            new DictionaryItem(ReviewerWhitelistRole.PROCESS_EXPERT.name(), ReviewerWhitelistRole.PROCESS_EXPERT.displayName()),
            new DictionaryItem(ReviewerWhitelistRole.PCB_EXPERT.name(), ReviewerWhitelistRole.PCB_EXPERT.displayName()),
            new DictionaryItem(ReviewerWhitelistRole.PCB_MUTUAL_CHECK.name(), ReviewerWhitelistRole.PCB_MUTUAL_CHECK.displayName()),
            new DictionaryItem(ReviewerWhitelistRole.SCHEMATIC_MUTUAL_CHECK.name(), ReviewerWhitelistRole.SCHEMATIC_MUTUAL_CHECK.displayName()));

    private static final List<DictionaryItem> TASK_STATUSES = java.util.Arrays.stream(TaskStatus.values())
            .map(status -> new DictionaryItem(status.name(), status.displayName()))
            .toList();

    @GetMapping("/task-options")
    @Operation(summary = "查询任务字典", description = "返回 PCB 板类型、创建任务可选的评审角色、白名单可配置角色和全部任务状态。code 是接口传参值，name 是前端显示中文名称；PCB/原理图互检职责只在白名单及分配流程使用，不在创建页选择。")
    public ApiResponse<TaskOptionsView> taskOptions(HttpServletRequest request) {
        return ApiResponse.ok(new TaskOptionsView(
                List.of("BMU板", "BSU板", "分流器板", "高压板", "转接板", "储能板", "其他"),
                List.of(
                        new DictionaryItem(ReviewRole.HARDWARE_EXPERT.name(), ReviewRole.HARDWARE_EXPERT.displayName()),
                        new DictionaryItem(ReviewRole.EMC_EXPERT.name(), ReviewRole.EMC_EXPERT.displayName()),
                        new DictionaryItem(ReviewRole.PCB_EXPERT.name(), ReviewRole.PCB_EXPERT.displayName()),
                        new DictionaryItem(ReviewRole.PROCESS_EXPERT.name(), ReviewRole.PROCESS_EXPERT.displayName()),
                        new DictionaryItem(ReviewRole.STRUCTURE_EXPERT.name(), ReviewRole.STRUCTURE_EXPERT.displayName())),
                REVIEWER_WHITELIST_ROLES,
                TASK_STATUSES), traceId(request));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }

    @Schema(description = "任务创建页面所需字典集合")
    public record TaskOptionsView(@Schema(description = "PCB 评审类型下拉选项") List<String> pcbTypes,
                                  @Schema(description = "评审角色多选选项") List<DictionaryItem> reviewRoles,
                                  @Schema(description = "评审人员白名单允许配置的角色选项") List<DictionaryItem> reviewerWhitelistRoles,
                                  @Schema(description = "任务状态枚举选项，可用于任务列表状态筛选和状态中文展示") List<DictionaryItem> taskStatuses) { }
    @Schema(description = "字典选项")
    public record DictionaryItem(@Schema(description = "提交给接口的枚举编码") String code,
                                 @Schema(description = "页面展示中文名称") String name) { }
}
