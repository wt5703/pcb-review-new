package com.bms.identity.infrastructure;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户中心“当前用户”接口的适配器。
 *
 * <p>PCB 系统不再接受前端传递的用户 ID、工号或角色。配置了正式环境当前用户地址后，本类转发
 * 当前请求的 Authorization 请求头并读取用户中心返回的身份。该地址不包含员工工号，由用户中心根据登录态识别当前人员；
 * 联调期间地址未配置或调用失败时，返回拥有全部权限的王鹏飞 Mock 用户。</p>
 */
@Component
public class UserCenterCurrentUserClient {
    private static final CurrentUser DEFAULT_MOCK_USER = new CurrentUser("BMS001", "王鹏飞", EnumSet.allOf(Role.class));

    private final RestClient restClient;
    private final String currentUserUrl;

    public UserCenterCurrentUserClient(@Value("${user-center.current-user-url}") String currentUserUrl) {
        this.restClient = RestClient.create();
        this.currentUserUrl = currentUserUrl;
    }

    public CurrentUser currentUser(HttpServletRequest request) {
        if (currentUserUrl == null || currentUserUrl.isBlank()) {
            return DEFAULT_MOCK_USER;
        }
        try {
            UserCenterResponse response = restClient.get()
                    .uri(currentUserUrl)
                    .headers(headers -> forwardAuthorization(request, headers))
                    .retrieve()
                    .body(UserCenterResponse.class);
            if (response == null || response.code() == null || response.code() != 0 || response.data() == null
                    || response.data().leapId() == null || response.data().leapId().isBlank()) {
                return DEFAULT_MOCK_USER;
            }
            UserCenterUser user = response.data();
            return new CurrentUser(user.leapId(), displayName(user), user.email(), user.shortPhone(), user.deptName(),
                    resolveBusinessRoles(user.roles()));
        } catch (Exception ignored) {
            return DEFAULT_MOCK_USER;
        }
    }

    private String displayName(UserCenterUser user) {
        return user.name() == null || user.name().isBlank() ? user.leapId() : user.name();
    }

    /** 用户中心可返回多个应用的角色，仅识别本系统已定义的业务角色编码；其他角色不导致当前用户解析失败。 */
    private Set<Role> resolveBusinessRoles(List<UserCenterRoleGroup> roleGroups) {
        if (roleGroups == null) {
            return Set.of();
        }
        return roleGroups.stream().filter(Objects::nonNull)
                .flatMap(group -> group.roles() == null ? java.util.stream.Stream.empty() : group.roles().stream())
                .filter(Objects::nonNull).map(UserCenterRole::code).filter(Objects::nonNull).map(String::trim)
                .map(this::toBusinessRole).flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Optional<Role> toBusinessRole(String roleCode) {
        try {
            return Optional.of(Role.valueOf(roleCode));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private void forwardAuthorization(HttpServletRequest request, HttpHeaders headers) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    /** 用户中心当前用户接口响应：登录态确定人员，无需由 PCB 前端或后端传递员工工号。 */
    record UserCenterResponse(Integer code, String msg, UserCenterUser data) { }
    record UserCenterUser(String leapId, String name, String originName, String email, String shortPhone, String deptName,
                          List<UserCenterRoleGroup> roles) { }
    record UserCenterRoleGroup(UserCenterApplication app, List<UserCenterRole> roles) { }
    record UserCenterApplication(String code, String name) { }
    record UserCenterRole(String code, String name) { }
}
