package com.bms.common.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.review.domain.ReviewRole;
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
    @GetMapping("/task-options")
    @Operation(summary = "查询任务创建字典", description = "返回 PCB 评审类型及 ReviewRole 枚举。code 是接口传参值，name 是前端显示中文名称。")
    public ApiResponse<TaskOptionsView> taskOptions(HttpServletRequest request) {
        return ApiResponse.ok(new TaskOptionsView(
                List.of("BMU板", "BSU板", "分流器板", "高压板", "转接板", "储能板", "其他"),
                List.of(
                        new DictionaryItem(ReviewRole.PCB_EXPERT.name(), "PCB评审"),
                        new DictionaryItem(ReviewRole.PROCESS_EXPERT.name(), "工艺评审"),
                        new DictionaryItem(ReviewRole.STRUCTURE_EXPERT.name(), "结构评审"),
                        new DictionaryItem(ReviewRole.PCB_MUTUAL_CHECK.name(), "PCB互检"),
                        new DictionaryItem(ReviewRole.SCHEMATIC_HARDWARE_EXPERT.name(), "硬件评审"),
                        new DictionaryItem(ReviewRole.SCHEMATIC_OTHER_EXPERT.name(), "原理图其他评审"),
                        new DictionaryItem(ReviewRole.SCHEMATIC_MUTUAL_CHECK.name(), "原理图互检"))), traceId(request));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }

    @Schema(description = "任务创建页面所需字典集合")
    public record TaskOptionsView(@Schema(description = "PCB 评审类型下拉选项") List<String> pcbTypes,
                                  @Schema(description = "评审角色多选选项") List<DictionaryItem> reviewRoles) { }
    @Schema(description = "字典选项")
    public record DictionaryItem(@Schema(description = "提交给接口的枚举编码") String code,
                                 @Schema(description = "页面展示中文名称") String name) { }
}
