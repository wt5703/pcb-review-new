package com.leapmotor.pcbreview.identity.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 配置本地 Mock 身份模式下的 Spring Security 过滤链，使业务授权由应用层权限策略统一处理并避免表单登录干扰接口测试。
 */


@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
