package com.bms.identity.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestHeader;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.net.URLDecoder.decode;

/** 为兼容已有接口，直接返回请求头中的人员工号与姓名。 */
@RestController
@Validated
@RequestMapping("/identity")
@Tag(name = "当前登录用户", description = "返回请求头中的用户工号和姓名。")
public class CurrentUserController {
    @GetMapping("/current-user")
    @Operation(summary = "获取当前登录用户详情", description = "从请求头 userId 和 userName 读取工号和姓名，不调用用户中心。")
    ApiResponse<CurrentUserView> currentUser(@NotBlank @RequestHeader("userId") String employeeNo,
                                            @NotBlank @RequestHeader("userName") String userName, HttpServletRequest request) {
        userName = decode(userName, StandardCharsets.UTF_8);
        return ApiResponse.ok(new CurrentUserView(employeeNo, userName, null, null, null, List.of()),
                request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString());
    }

    public record CurrentUserView(String employeeNo, String displayName, String email, String mobile,
                                  String departmentName, List<String> roles) { }
}
