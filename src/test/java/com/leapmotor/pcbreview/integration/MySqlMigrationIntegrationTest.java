package com.leapmotor.pcbreview.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 在显式启用时连接本机 MySQL，验证 Flyway 已执行到当前最新迁移版本，避免 H2 兼容模式掩盖真实数据库差异。
 */
@SpringBootTest
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named = "PCB_REVIEW_RUN_MYSQL_TESTS", matches = "(?i)true")
class MySqlMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Flyway flyway;

    @Test
    void shouldConnectToMysqlAndReachLatestMigration() throws SQLException {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).containsIgnoringCase("mysql");
        }
    }
}
