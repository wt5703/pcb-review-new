package com.bms.review.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.domain.ReviewRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-22
 * @description 对外提供评审角色白名单的批量新增入口，保存角色与员工工号的一对多映射。
 */
@RestController
@RequestMapping("/reviewer-whitelists")
@Tag(name = "评审人员白名单", description = "维护硬件、EMC、结构、工艺和 PCB 评审角色可选员工工号。")
public class ReviewerWhitelistController {
    private final ReviewerWhitelistApplicationService whitelistApplicationService;

    public ReviewerWhitelistController(ReviewerWhitelistApplicationService whitelistApplicationService) {
        this.whitelistApplicationService = whitelistApplicationService;
    }

    @GetMapping
    @Operation(summary = "查询评审人员白名单", description = "返回当前启用白名单的姓名、工号、邮箱、手机号、评审角色、创建时间及主键。前端使用主键精确删除单一角色映射。")
    ApiResponse<List<ReviewerWhitelistApplicationService.ReviewerWhitelistView>> list(HttpServletRequest servletRequest) {
        return ApiResponse.ok(whitelistApplicationService.list(CurrentUserHolder.require()), traceId(servletRequest));
    }

    @PostMapping
    @Operation(summary = "批量新增评审人员白名单", description = "仅支持 意见评审=HARDWARE_EXPERT、 EMC评审=EMC_EXPERT、结构评审=STRUCTURE_EXPERT、工艺评审=PROCESS_EXPERT、PCB评审=PCB_EXPERT；同一角色与工号的重复映射会被忽略")
    ApiResponse<ReviewerWhitelistApplicationService.SaveResult> add(
            @Valid @RequestBody AddReviewerWhitelistRequest request,
            HttpServletRequest servletRequest) {
        List<ReviewerWhitelistApplicationService.RoleEmployeeNos> mappings = request.roleEmployeeNos().stream()
                .map(item -> new ReviewerWhitelistApplicationService.RoleEmployeeNos(item.reviewRole(), item.employeeNos()))
                .toList();
        return ApiResponse.ok(whitelistApplicationService.add(mappings, CurrentUserHolder.require()), traceId(servletRequest));
    }

    @DeleteMapping
    @Operation(summary = "逻辑删除评审人员白名单", description = "必须且只能传 id 或 employeeNo。id 只删除该条角色—工号映射；employeeNo 删除该员工工号全部启用的白名单角色映射。删除后不物理删除数据，后续新增相同角色与工号会恢复映射。")
    ApiResponse<ReviewerWhitelistApplicationService.DeleteResult> remove(
            @RequestParam(required = false) @Schema(description = "白名单主键；与 employeeNo 二选一") Long id,
            @RequestParam(required = false) @Schema(description = "员工工号；与 id 二选一，传入后删除该工号下所有白名单角色映射") String employeeNo,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(whitelistApplicationService.remove(id, employeeNo, CurrentUserHolder.require()), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString();
    }

    @Schema(description = "批量新增评审白名单请求")
    record AddReviewerWhitelistRequest(
            @Schema(description = "角色与员工工号映射；同一角色只能出现一次", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotEmpty List<@Valid RoleEmployeeNosRequest> roleEmployeeNos) { }

    @Schema(description = "单个评审角色与多个员工工号")
    record RoleEmployeeNosRequest(
            @Schema(description = "评审角色", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ReviewRole reviewRole,
            @Schema(description = "该角色对应的员工工号列表", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotEmpty List<@NotBlank String> employeeNos) { }
}
