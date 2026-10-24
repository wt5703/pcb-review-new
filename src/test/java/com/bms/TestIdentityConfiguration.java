package com.bms;

import com.bms.notification.application.MailGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 集成测试使用测试专属的空邮件网关，用户身份与生产代码一样从请求头读取。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestIdentityConfiguration {
    @Bean
    @Primary
    MailGateway testMailGateway() {
        return message -> { };
    }

    /** 不影响测试中显式指定的人员，请求未指定身份时使用测试默认请求头。 */
    @Bean
    MockMvcBuilderCustomizer defaultTestHeaders() {
        return builder -> builder.defaultRequest(get("/").header("userId", "BMS001").header("userName", "测试用户"));
    }
}
