package com.bms;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 用旧主键和旧白名单删除标记验证增量升级，不依赖应用初始化后的新表结构。 */
class DeleteStatusMigrationTest {
    @Test
    void upgradesLegacyTablesAndPreservesDeletionStateAndExistingSubmissions() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:delete_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        // 保持此连接打开，避免独立迁移库在两次 JDBC 操作之间被关闭。
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(dataSource);
            for (String table : List.of("review_task", "review_opinion", "review_file", "opinion_reply", "opinion_confirmation",
                    "check_item_template", "task_flow_record", "outbox_event", "notification_send_record", "task_archive_snapshot")) {
                jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY)");
            }
            jdbc.execute("CREATE TABLE reviewer_whitelist (id BIGINT PRIMARY KEY, deleted BOOLEAN NOT NULL DEFAULT FALSE)");
            jdbc.execute("CREATE TABLE reviewer_whitelist_role (id BIGINT PRIMARY KEY, deleted BOOLEAN NOT NULL DEFAULT FALSE)");
            jdbc.execute("CREATE TABLE review_submission (task_id BIGINT NOT NULL, source_type VARCHAR(64) NOT NULL, reviewer_employee_no VARCHAR(64) NOT NULL, submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(task_id, source_type, reviewer_employee_no))");
            jdbc.execute("CREATE TABLE task_check_item (id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, item_id BIGINT NOT NULL, UNIQUE KEY uk_task_check_item(task_id,item_id))");
            jdbc.update("INSERT INTO reviewer_whitelist VALUES (1,FALSE),(2,TRUE)");
            jdbc.update("INSERT INTO reviewer_whitelist_role VALUES (1,FALSE),(2,TRUE)");
            jdbc.update("INSERT INTO review_submission(task_id,source_type,reviewer_employee_no) VALUES (1,'PCB_REVIEW','R')");
            jdbc.update("INSERT INTO task_check_item VALUES (1,1,10)");
            var migration = new ResourceDatabasePopulator(new FileSystemResource("docs/database/delete-status-upgrade.sql"));
            migration.setSqlScriptEncoding("UTF-8");
            migration.execute(dataSource);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='delete_status'", Integer.class)).isEqualTo(14);
            assertThat(jdbc.queryForList("SELECT delete_status FROM reviewer_whitelist ORDER BY id", Integer.class)).containsExactly(0, 1);
            assertThat(jdbc.queryForList("SELECT delete_status FROM reviewer_whitelist_role ORDER BY id", Integer.class)).containsExactly(0, 1);
            jdbc.update("UPDATE review_submission SET delete_status=1 WHERE task_id=1");
            jdbc.update("INSERT INTO review_submission(task_id,source_type,reviewer_employee_no) VALUES (1,'PCB_REVIEW','R')");
            jdbc.update("UPDATE task_check_item SET delete_status=1 WHERE id=1");
            jdbc.update("INSERT INTO task_check_item(id,task_id,item_id) VALUES (2,1,10)");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_submission WHERE task_id=1", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_check_item WHERE task_id=1", Integer.class)).isEqualTo(2);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
