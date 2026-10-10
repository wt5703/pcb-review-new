package com.bms.identity.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.CurrentUserHolder;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前登录用户资料由服务端身份上下文与用户中心共同解析，前端不传员工工号。 */
@RestController
@RequestMapping("/identity")
@Tag(name = "当前登录用户", description = "返回当前请求对应的用户中心资料，不接收前端用户 ID 或员工工号。")
public class CurrentUserController {
    private final UserCenterUserProfileClient userProfileClient;

    public CurrentUserController(UserCenterUserProfileClient userProfileClient) {
        this.userProfileClient = userProfileClient;
    }

    @GetMapping("/current-user")
    @Operation(summary = "获取当前登录用户详情", description = "不传任何人员参数。后端从当前登录身份取得员工工号，再查询用户中心获取姓名、邮箱、手机号和部门。")
    ApiResponse<CurrentUserView> currentUser(HttpServletRequest request) {
        CurrentUser currentUser = CurrentUserHolder.require();
        UserCenterUserProfileClient.UserProfile profile = userProfileClient.getCurrentUserProfile(currentUser.employeeNo());
        return ApiResponse.ok(new CurrentUserView(currentUser.employeeNo(), profile.displayName(), profile.email(),
                        profile.mobile(), profile.departmentName(),
                        currentUser.roles().stream().map(Enum::name).sorted().toList()),
                request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString());
    }

    public record CurrentUserView(String employeeNo, String displayName, String email, String mobile,
                                  String departmentName, List<String> roles) { }
}
