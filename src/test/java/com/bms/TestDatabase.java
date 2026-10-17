package com.bms;

import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 每个集成测试方法重建独立测试数据，确保重复执行和测试顺序不会相互影响。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Sql(scripts = {"classpath:db/reset.sql", "file:docs/database/ddl.sql", "file:docs/database/init.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
@Import(TestIdentityConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pcb_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "user-center.current-user-url=", "user-center.user-profile-url-template=",
        "resource-service.mock-enabled=true",
        "notification.dispatch.initial-delay-ms=86400000"
})
public @interface TestDatabase {
}
