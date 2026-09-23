package com.bms.identity.interfaces;

import com.bms.common.ApiResponse;
import com.bms.common.TraceIdFilter;
import com.bms.identity.application.MockUserDirectoryApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 提供仅限本地联调环境的初始化账号目录，帮助前端选择真实的模拟人员与其预设角色，不替代生产用户管理接口。
 */
@RestController
@RequestMapping("/mock-users")
@Tag(name = "本地 Mock 用户", description = "仅用于开发联调的初始化账号、部门、邮箱、手机号和角色目录；生产环境由统一身份系统替代。")
public class MockUserController {
    private final MockUserDirectoryApplicationService mockUserDirectoryApplicationService;

    public MockUserController(MockUserDirectoryApplicationService mockUserDirectoryApplicationService) {
        this.mockUserDirectoryApplicationService = mockUserDirectoryApplicationService;
    }

    @GetMapping
    @Operation(summary = "查询本地初始化账号", description = "返回可用于本地 Mock 身份切换和评审白名单选择的账号 ID、员工工号、姓名、测试邮箱、手机号、所属部门和预设角色。")
    public ApiResponse<List<MockUserDirectoryApplicationService.MockUserView>> list(HttpServletRequest request) {
        return ApiResponse.ok(mockUserDirectoryApplicationService.listEnabledUsers(), traceId(request));
    }

    private String traceId(HttpServletRequest request) { return request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE).toString(); }
}
