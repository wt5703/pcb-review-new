package com.bms.identity.infrastructure;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户中心“当前用户”接口的适配器。
 *
 * <p>PCB 系统不再接受前端传递的用户 ID 或角色。配置了用户中心地址后，本类转发
 * 当前请求的 Authorization 请求头并读取用户中心返回的身份；联调期间地址未配置或调用
 * 失败时，返回拥有全部权限的王鹏飞 Mock 用户。</p>
 */
@Component
public class UserCenterCurrentUserClient {
    private static final CurrentUser DEFAULT_MOCK_USER = new CurrentUser(1L, "王鹏飞", EnumSet.allOf(Role.class));

    private final RestClient restClient;
    private final String currentUserUrl;

    public UserCenterCurrentUserClient(@Value("${user-center.current-user-url:}") String currentUserUrl) {
        this.restClient = RestClient.create();
        this.currentUserUrl = currentUserUrl;
    }

    public CurrentUser currentUser(HttpServletRequest request) {
        if (currentUserUrl == null || currentUserUrl.isBlank()) {
            return DEFAULT_MOCK_USER;
        }
        try {
            UserCenterUser response = restClient.get()
                    .uri(currentUserUrl)
                    .headers(headers -> forwardAuthorization(request, headers))
                    .retrieve()
                    .body(UserCenterUser.class);
            if (response == null || response.userId() == null || response.displayName() == null || response.displayName().isBlank()) {
                return DEFAULT_MOCK_USER;
            }
            Set<Role> roles = response.roles() == null ? Set.of() : response.roles().stream()
                    .filter(role -> role != null && !role.isBlank())
                    .map(String::trim)
                    .map(Role::valueOf)
                    .collect(Collectors.toUnmodifiableSet());
            return new CurrentUser(response.userId(), response.displayName(), roles);
        } catch (Exception ignored) {
            return DEFAULT_MOCK_USER;
        }
    }

    private void forwardAuthorization(HttpServletRequest request, HttpHeaders headers) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    /** 约定用户中心当前用户接口返回 userId、employeeNo、displayName 和 roles。 */
    record UserCenterUser(Long userId, String employeeNo, String displayName, Set<String> roles) { }
}
