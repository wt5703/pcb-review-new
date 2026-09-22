package com.bms.common.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.review.domain.ReviewRole;
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
            new DictionaryItem(ReviewRole.HARDWARE_EXPERT.name(), "硬件评审"),
            new DictionaryItem(ReviewRole.EMC_EXPERT.name(), "EMC评审"),
            new DictionaryItem(ReviewRole.STRUCTURE_EXPERT.name(), "结构评审"),
            new DictionaryItem(ReviewRole.PROCESS_EXPERT.name(), "工艺评审"),
            new DictionaryItem(ReviewRole.PCB_EXPERT.name(), "PCB评审"));

    private static final List<DictionaryItem> TASK_STATUSES = List.of(
            new DictionaryItem(TaskStatus.DRAFT.name(), "草稿"),
            new DictionaryItem(TaskStatus.PCB_PENDING_REVIEW.name(), "待专家评审"),
            new DictionaryItem(TaskStatus.PCB_EXPERT_REVIEWING.name(), "专家评审中"),
            new DictionaryItem(TaskStatus.PCB_DESIGNER_REPLYING.name(), "设计者答复中"),
            new DictionaryItem(TaskStatus.PCB_OPTIONAL_REVIEWING.name(), "工艺/结构评审中"),
            new DictionaryItem(TaskStatus.PCB_OPTIONAL_DESIGNER_REPLYING.name(), "设计者答复中"),
            new DictionaryItem(TaskStatus.PENDING_MUTUAL_ASSIGNMENT.name(), "待互检单分配"),
            new DictionaryItem(TaskStatus.MUTUAL_REVIEWING.name(), "互检中"),
            new DictionaryItem(TaskStatus.PCB_MUTUAL_DESIGNER_REPLYING.name(), "设计者答复中"),
            new DictionaryItem(TaskStatus.SCHEMATIC_PENDING_LEADER_ASSIGNMENT.name(), "待组长分配"),
            new DictionaryItem(TaskStatus.SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT.name(), "待互检单分配"),
            new DictionaryItem(TaskStatus.SCHEMATIC_MUTUAL_DESIGNER_REPLYING.name(), "设计者答复中"),
            new DictionaryItem(TaskStatus.SCHEMATIC_PENDING_REVIEW.name(), "待原理图专家分配"),
            new DictionaryItem(TaskStatus.HARDWARE_REVIEWING.name(), "原理图评审中"),
            new DictionaryItem(TaskStatus.SCHEMATIC_DESIGNER_REPLYING.name(), "设计者答复中"),
            new DictionaryItem(TaskStatus.PENDING_FINISH_CONFIRMATION.name(), "待结束确认"),
            new DictionaryItem(TaskStatus.FINISHED.name(), "已结束"));

    @GetMapping("/task-options")
    @Operation(summary = "查询任务字典", description = "返回 PCB 板类型、创建任务可选的评审角色、白名单可配置角色和全部任务状态。code 是接口传参值，name 是前端显示中文名称；互检职责和原理图流程内部职责不在创建页选择。")
    public ApiResponse<TaskOptionsView> taskOptions(HttpServletRequest request) {
        return ApiResponse.ok(new TaskOptionsView(
                List.of("BMU板", "BSU板", "分流器板", "高压板", "转接板", "储能板", "其他"),
                List.of(
                        new DictionaryItem(ReviewRole.HARDWARE_EXPERT.name(), "硬件评审"),
                        new DictionaryItem(ReviewRole.EMC_EXPERT.name(), "EMC评审"),
                        new DictionaryItem(ReviewRole.PCB_EXPERT.name(), "PCB评审"),
                        new DictionaryItem(ReviewRole.PROCESS_EXPERT.name(), "工艺评审"),
                        new DictionaryItem(ReviewRole.STRUCTURE_EXPERT.name(), "结构评审")),
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
