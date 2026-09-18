package com.leapmotor.pcbreview.common.config;

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
        description = "本地 Mock 身份通过 X-Mock-User-Id 与 X-Mock-Roles 请求头传入；多角色使用英文逗号分隔。"))
@SecurityScheme(name = "mockUserId", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Mock-User-Id", description = "本地 Mock 当前用户 ID")
@SecurityScheme(name = "mockRoles", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Mock-Roles", description = "本地 Mock 角色，例如 DESIGNER 或 PCB_LEADER")
public class OpenApiConfiguration {
}
