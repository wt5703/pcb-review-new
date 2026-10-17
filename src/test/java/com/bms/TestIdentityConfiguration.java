package com.bms;

import com.bms.identity.application.CurrentUser;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.UserCenterCurrentUserClient;
import com.bms.notification.application.MailGateway;
import com.bms.notification.infrastructure.MockMailGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
import java.util.stream.Collectors;

/** 仅在集成测试中从测试请求头模拟用户，不改变生产环境身份认证逻辑。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestIdentityConfiguration {
    @Bean
    @Primary
    MailGateway testMailGateway() {
        return new MockMailGateway();
    }

    @Bean
    @Primary
    UserCenterCurrentUserClient testCurrentUserClient() {
        return new UserCenterCurrentUserClient("") {
            @Override
            public CurrentUser currentUser(HttpServletRequest request) {
                String employeeNo = request.getHeader("X-Mock-Employee-No");
                String oldId = request.getHeader("X-Mock-User-Id");
                if ((employeeNo == null || employeeNo.isBlank()) && oldId != null) {
                    employeeNo = "BMS%03d".formatted(Integer.parseInt(oldId));
                }
                if (employeeNo == null || employeeNo.isBlank()) return super.currentUser(request);
                String roleHeader = request.getHeader("X-Mock-Roles");
                var roles = roleHeader == null || roleHeader.isBlank() ? java.util.Set.<Role>of()
                        : Arrays.stream(roleHeader.split(",")).map(String::trim).map(Role::valueOf).collect(Collectors.toSet());
                return new CurrentUser(employeeNo, employeeNo, roles);
            }
        };
    }
}
