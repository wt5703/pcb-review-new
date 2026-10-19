package com.bms.review.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewerWhitelistRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 对外提供评审角色白名单的批量新增入口，保存角色与员工工号的一对多映射。
 */
@RestController
@Validated
@RequestMapping("/reviewer-whitelists")
@Tag(name = "评审人员白名单", description = "维护硬件、EMC、结构、工艺、PCB 评审及 PCB/原理图互检职责的可选员工工号。")
public class ReviewerWhitelistController {
    private final ReviewerWhitelistApplicationService whitelistApplicationService;

    public ReviewerWhitelistController(ReviewerWhitelistApplicationService whitelistApplicationService) {
        this.whitelistApplicationService = whitelistApplicationService;
    }

    @PostMapping("/query")
    @Operation(summary = "分页查询评审人员白名单", description = "按人员分页，每人返回一条及全部有效角色 reviewRoles；keyword 支持工号或姓名，分页参数为 pageNum、pageSize。")
    ApiResponse<ReviewerWhitelistApplicationService.ReviewerWhitelistPage> list(
            @RequestBody(required = false) ReviewerWhitelistQueryRequest request,
            HttpServletRequest servletRequest) {
        ReviewerWhitelistQueryRequest query = request == null ? new ReviewerWhitelistQueryRequest(null, null, null) : request;
        return ApiResponse.ok(whitelistApplicationService.listPage(
                new ReviewerWhitelistApplicationService.ReviewerWhitelistQuery(query.keyword(), query.pageNum(), query.pageSize()),
                null, null), traceId(servletRequest));
    }

    @GetMapping("/people")
    @Operation(summary = "查询白名单人员", description = "无需传参，返回 Map：key 为角色、value 为该角色下的人员列表；同一人可同时出现在多个角色下。")
    ApiResponse<Map<ReviewerWhitelistRole, List<ReviewerWhitelistApplicationService.ReviewerWhitelistView>>> people(HttpServletRequest servletRequest) {
        return ApiResponse.ok(whitelistApplicationService.listPeopleGroupedByRole(), traceId(servletRequest));
    }

    @PostMapping
    @Operation(summary = "批量新增评审人员白名单", description = "按角色批量新增白名单。硬件评审=HARDWARE_EXPERT、EMC评审=EMC_EXPERT、结构评审=STRUCTURE_EXPERT、工艺评审=PROCESS_EXPERT、PCB评审=PCB_EXPERT、PCB互检=PCB_MUTUAL_CHECK、原理图互检=SCHEMATIC_MUTUAL_CHECK")
    ApiResponse<ReviewerWhitelistApplicationService.SaveResult> add(
            @Valid @RequestBody AddReviewerWhitelistRequest request,
            @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        List<ReviewerWhitelistApplicationService.RoleEmployeeNos> mappings = request.roleEmployeeNos().stream()
                .map(item -> new ReviewerWhitelistApplicationService.RoleEmployeeNos(item.reviewRole(), item.employeeNos()))
                .toList();
        return ApiResponse.ok(whitelistApplicationService.add(mappings, employeeNo, null), traceId(servletRequest));
    }

    @DeleteMapping
    @Operation(summary = "逻辑删除评审人员白名单", description = "必须且只能传人员 id 或 employeeNo；删除该人员及其全部角色。")
    ApiResponse<ReviewerWhitelistApplicationService.DeleteResult> remove(
            @RequestParam(required = false) @Schema(description = "白名单主键；与 employeeNo 二选一") Long id,
            @RequestParam(required = false) @Schema(description = "员工工号；与 id 二选一，传入后删除该工号下所有白名单角色映射") String employeeNo,
            @NotBlank @RequestHeader("userId") String operatorEmployeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(whitelistApplicationService.remove(id, employeeNo, operatorEmployeeNo, null), traceId(servletRequest));
    }

    @DeleteMapping("/{id}/roles/{role}")
    @Operation(summary = "删除白名单人员的指定角色", description = "仅删除指定角色；若该人员没有其他有效角色，则同时停用人员。")
    ApiResponse<ReviewerWhitelistApplicationService.DeleteResult> removeRole(@PathVariable long id,
            @PathVariable ReviewerWhitelistRole role, @NotBlank @RequestHeader("userId") String employeeNo, HttpServletRequest servletRequest) {
        return ApiResponse.ok(whitelistApplicationService.removeRole(id, role, employeeNo, null), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "批量新增评审白名单请求")
    record AddReviewerWhitelistRequest(
            @Schema(description = "角色与员工工号映射；同一角色只能出现一次", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotEmpty List<@Valid RoleEmployeeNosRequest> roleEmployeeNos) { }

    @Schema(description = "白名单分页查询条件；keyword 同时匹配员工工号和姓名")
    record ReviewerWhitelistQueryRequest(
            @Schema(description = "查询关键词，按工号或姓名模糊匹配") String keyword,
            @Schema(description = "页码，从 1 开始，默认 1") Integer pageNum,
            @Schema(description = "每页条数，默认 20，最大 1000") Integer pageSize) { }

    @Schema(description = "单个评审角色与多个员工工号")
    record RoleEmployeeNosRequest(
            @Schema(description = "白名单人员类别", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewerWhitelistRole reviewRole,
            @Schema(description = "该角色对应的员工工号列表", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotEmpty List<@NotBlank String> employeeNos) { }
}
