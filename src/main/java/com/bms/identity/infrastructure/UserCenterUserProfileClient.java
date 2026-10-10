package com.bms.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 公司用户中心的人员资料查询适配器。
 *
 * <p>PCB 系统只保存员工工号；姓名、邮箱、手机号和部门等展示资料均以用户中心为准。
 * 接口地址由环境配置提供，调用失败时不影响白名单查询，仅回退展示员工工号而不伪造姓名。</p>
 */
@Component
public class UserCenterUserProfileClient {
    private final RestClient restClient;
    private final String profileUrlTemplate;
    private final String currentUserProfileUrlTemplate;

    public UserCenterUserProfileClient(
            @Value("${user-center.user-profile-url-template:}") String profileUrlTemplate,
            @Value("${user-center.current-user-profile-url-template:}") String currentUserProfileUrlTemplate) {
        this.restClient = RestClient.create();
        this.profileUrlTemplate = profileUrlTemplate;
        this.currentUserProfileUrlTemplate = currentUserProfileUrlTemplate;
    }

    public UserProfile getByEmployeeNo(String employeeNo) {
        return getByEmployeeNo(employeeNo, profileUrlTemplate);
    }

    /** 当前用户详情使用独立的配置路径；员工工号仅来自服务端当前身份，绝不由前端传入。 */
    public UserProfile getCurrentUserProfile(String employeeNo) {
        return getByEmployeeNo(employeeNo, currentUserProfileUrlTemplate);
    }

    private UserProfile getByEmployeeNo(String employeeNo, String urlTemplate) {
        if (employeeNo == null || employeeNo.isBlank()) {
            return UserProfile.empty(employeeNo);
        }
        if (urlTemplate == null || urlTemplate.isBlank()) {
            return mockOrEmpty(employeeNo);
        }
        try {
            UserCenterResponse response = restClient.get()
                    .uri(urlTemplate, employeeNo)
                    .headers(this::forwardAuthorization)
                    .retrieve()
                    .body(UserCenterResponse.class);
            if (response == null || response.code() != 0 || response.data() == null) {
                return mockOrEmpty(employeeNo);
            }
            UserCenterProfile data = response.data();
            return new UserProfile(employeeNo, displayName(data.name(), employeeNo), data.email(),
                    data.shortPhone(), data.deptName());
        } catch (Exception ignored) {
            return mockOrEmpty(employeeNo);
        }
    }

    /** 一次请求中的相同工号只查询一次，避免角色重复导致重复访问用户中心。 */
    public Map<String, UserProfile> getByEmployeeNos(List<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) {
            return Map.of();
        }
        return employeeNos.stream().filter(employeeNo -> employeeNo != null && !employeeNo.isBlank()).distinct()
                .collect(Collectors.toMap(employeeNo -> employeeNo, this::getByEmployeeNo, (left, right) -> left,
                        LinkedHashMap::new));
    }

    private UserProfile mockOrEmpty(String employeeNo) {
        return UserProfile.empty(employeeNo);
    }

    private void forwardAuthorization(HttpHeaders headers) {
        Optional.ofNullable(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes())
                .filter(attributes -> attributes instanceof org.springframework.web.context.request.ServletRequestAttributes)
                .map(attributes -> ((org.springframework.web.context.request.ServletRequestAttributes) attributes).getRequest())
                .map(request -> request.getHeader(HttpHeaders.AUTHORIZATION))
                .filter(value -> !value.isBlank())
                .ifPresent(value -> headers.set(HttpHeaders.AUTHORIZATION, value));
    }

    private String displayName(String name, String employeeNo) {
        return name == null || name.isBlank() ? employeeNo : name;
    }

    public record UserCenterResponse(int code, String msg, UserCenterProfile data) { }
    public record UserCenterProfile(String leapId, String name, String email, String shortPhone, String deptName) { }

    public record UserProfile(String employeeNo, String displayName, String email, String mobile, String departmentName) {
        static UserProfile empty(String employeeNo) {
            return new UserProfile(employeeNo, employeeNo, null, null, null);
        }
    }
}
