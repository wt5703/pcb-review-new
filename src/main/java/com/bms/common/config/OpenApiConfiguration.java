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
 * @description 配置后端 OpenAPI 文档元数据和本地 Mock 身份请求头说明，使接口联调人员可从 Swagger UI 获取当前可用接口契约。
 */
@Configuration
@OpenAPIDefinition(info = @Info(title = "PCB评审平台后端 API", version = "v1",
        description = "当前登录用户由后端调用用户中心当前用户接口获取；未配置或调用失败时使用默认 Mock 用户。"))
public class OpenApiConfiguration {
}
