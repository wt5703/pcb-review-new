package com.bms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 从未执行本次升级的旧表结构运行唯一增量脚本，验证最终结构、历史回填及有效记录唯一约束。 */
class UpgradeMigrationTest {
    private DriverManagerDataSource dataSource;
    private Connection connection;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createLegacySchema() throws SQLException {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:upgrade_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        connection = dataSource.getConnection();
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("review_file", "opinion_reply", "opinion_confirmation", "notification_send_record", "task_archive_snapshot")) {
            jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY)");
        }
        jdbc.execute("CREATE TABLE review_task (id BIGINT PRIMARY KEY, assigned_reviewer_employee_nos VARCHAR(2000))");
        jdbc.execute("CREATE TABLE review_opinion (id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, source_type VARCHAR(64) NOT NULL, raised_by_employee_no VARCHAR(64) NOT NULL, opinion_status VARCHAR(64) NOT NULL, is_draft BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_flow_record (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE check_item_template (id BIGINT PRIMARY KEY, item_name VARCHAR(200) NOT NULL)");
        jdbc.execute("CREATE TABLE outbox_event (id BIGINT PRIMARY KEY, aggregate_type VARCHAR(64), aggregate_id BIGINT)");
        jdbc.execute("CREATE TABLE reviewer_whitelist (id BIGINT PRIMARY KEY, deleted BOOLEAN NOT NULL DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE reviewer_whitelist_role (id BIGINT PRIMARY KEY, deleted BOOLEAN NOT NULL DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE task_check_item (id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, item_id BIGINT NOT NULL, UNIQUE KEY uk_task_check_item(task_id,item_id))");
        jdbc.update("INSERT INTO review_task VALUES (1,'BMS001')");
        jdbc.update("INSERT INTO reviewer_whitelist VALUES (1,FALSE),(2,TRUE)");
        jdbc.update("INSERT INTO reviewer_whitelist_role VALUES (1,FALSE),(2,TRUE)");
        jdbc.update("INSERT INTO task_check_item VALUES (1,1,10)");
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO review_opinion (id,task_id,source_type,raised_by_employee_no,opinion_status,is_draft) VALUES (?,1,?,?,'CONFIRMED_PASS',?)",
                    i, i == 4 ? "MUTUAL_EXTRA" : "PCB_REVIEW", "BMS00" + i, i == 5);
        }
        jdbc.update("INSERT INTO review_opinion (id,task_id,source_type,raised_by_employee_no,opinion_status) VALUES (6,1,'PCB_REVIEW','BMS003','PENDING_REPLY')");
        jdbc.update("INSERT INTO review_opinion (id,task_id,source_type,raised_by_employee_no,opinion_status) VALUES (7,1,'MUTUAL_CHECK_ITEM','BMS004','CONFIRMED_PASS')");
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    void upgradesAllTablesAndAllowsNewRowsAfterLogicalDeletion() {
        migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='delete_status'", Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForList("SELECT delete_status FROM reviewer_whitelist ORDER BY id", Integer.class)).containsExactly(0, 1);
        assertThat(jdbc.queryForList("SELECT delete_status FROM reviewer_whitelist_role ORDER BY id", Integer.class)).containsExactly(0, 1);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='task_flow_record'", String.class))
                .contains("from_status", "to_status", "next_operator_employee_nos", "approval_role");
        jdbc.update("INSERT INTO review_task (assigned_reviewer_employee_nos) VALUES ('R')");
        assertThat(jdbc.queryForObject("SELECT MAX(id) FROM review_task", Long.class)).isGreaterThan(1L);
        jdbc.update("INSERT INTO check_item_template (id,item_name) VALUES (1,?)", "检查".repeat(500));
        assertThat(jdbc.queryForObject("SELECT LENGTH(item_name) FROM check_item_template WHERE id=1", Integer.class)).isEqualTo(1000);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO review_submission(task_id,source_type,reviewer_employee_no) VALUES (1,'PCB_REVIEW','BMS002')"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        jdbc.update("UPDATE review_submission SET delete_status=1 WHERE task_id=1");
        jdbc.update("INSERT INTO review_submission(task_id,source_type,reviewer_employee_no) VALUES (1,'PCB_REVIEW','BMS002')");
        jdbc.update("UPDATE task_check_item SET delete_status=1 WHERE id=1");
        jdbc.update("INSERT INTO task_check_item(id,task_id,item_id) VALUES (2,1,10)");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_submission WHERE task_id=1 AND reviewer_employee_no='BMS002'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_check_item WHERE task_id=1", Integer.class)).isEqualTo(2);
    }

    @Test
    void backfillsOnlyClosedReviewsRemovedFromActiveAssignments() {
        migrate();
        assertThat(jdbc.queryForList("SELECT CONCAT(source_type, ':', reviewer_employee_no) FROM review_submission ORDER BY reviewer_employee_no", String.class))
                .containsExactly("PCB_REVIEW:BMS002", "MUTUAL_CHECK_ITEM:BMS004");
    }

    private void migrate() {
        var migration = new ResourceDatabasePopulator(new FileSystemResource("docs/database/upgrade.sql"));
        migration.setSqlScriptEncoding("UTF-8");
        migration.execute(dataSource);
    }
}
