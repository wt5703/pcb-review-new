package com.bms.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 配置后端 OpenAPI 文档元数据及请求头说明。
 */
@Configuration
@OpenAPIDefinition(info = @Info(title = "PCB评审平台后端 API", version = "v1",
        description = "业务接口按需通过请求头 userId 获取员工工号、userName 获取姓名；暂不校验角色权限。"))
public class OpenApiConfiguration {
}
