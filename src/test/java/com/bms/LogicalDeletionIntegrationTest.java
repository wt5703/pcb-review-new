package com.bms;

import com.bms.archive.infrastructure.TaskArchiveSnapshotMapper;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewerWhitelistMapper;
import com.bms.review.infrastructure.TaskCheckItemMapper;
import com.bms.review.infrastructure.TaskCheckItemRecord;
import com.bms.task.application.TaskApplicationService;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证统一删除标记、关联数据保留、重复删除保护、白名单原记录恢复及重新提交的唯一约束。 */
@SpringBootTest
@TestDatabase
class LogicalDeletionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskApplicationService tasks;
    @Autowired ReviewTaskMapper taskMapper;
    @Autowired ReviewOpinionMapper opinions;
    @Autowired ReviewFileMapper files;
    @Autowired TaskCheckItemMapper checks;
    @Autowired CheckItemTemplateMapper templates;
    @Autowired TaskFlowMapper flows;
    @Autowired OutboxEventMapper outbox;
    @Autowired NotificationSendRecordMapper notifications;
    @Autowired TaskArchiveSnapshotMapper archives;
    @Autowired ReviewerWhitelistMapper whitelist;

    @Test
    void everyBusinessTableHasNonNullZeroDefaultDeletionStatus() {
        var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'", String.class);
        assertThat(tables).hasSize(14);
        for (String table : tables) {
            var columns = jdbc.queryForList("SELECT column_default, is_nullable, data_type FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name='delete_status'", table);
            assertThat(columns).as(table).hasSize(1);
            assertThat(columns.get(0).get("column_default")).isEqualTo("0");
            assertThat(columns.get(0).get("is_nullable")).isEqualTo("NO");
            assertThat(columns.get(0).get("data_type").toString()).isEqualToIgnoringCase("TINYINT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE delete_status<>0 OR delete_status IS NULL", Integer.class)).isZero();
        }
    }

    @Test
    void taskDeletionPreservesAllRelatedRowsAndPreventsReadsAndOrdinaryUpdates() {
        insertTask();
        jdbc.update("INSERT INTO review_opinion (id,task_id,source_type,severity,raised_by_employee_no,raised_by_name,opinion_status) VALUES (99101,99100,'PCB_REVIEW','GENERAL','R','专家','PENDING_REPLY')");
        jdbc.update("INSERT INTO opinion_reply (id,opinion_id,reply_type,replied_by_employee_no,reply_no) VALUES (99102,99101,'ACCEPT','D',1)");
        jdbc.update("INSERT INTO opinion_confirmation (id,opinion_id,reply_id,passed,confirmed_by_employee_no) VALUES (99103,99101,99102,TRUE,'R')");
        opinions.insertSubmission(99100L, OpinionSourceType.PCB_REVIEW, "R");
        jdbc.update("INSERT INTO task_check_item (id,task_id,item_id) VALUES (99104,99100,99105)");
        jdbc.update("INSERT INTO review_file (id,file_id,task_id,file_category,file_name,file_format,file_size,md5,resource_path,is_latest,uploaded_by_employee_no) VALUES (99106,'delete-file',99100,'PCB_REVIEW','a.pcb','pcb',1,'md5','mock',TRUE,'D')");
        jdbc.update("INSERT INTO task_flow_record (id,task_id,action_code,action_name,operate_employee_no) VALUES (99107,99100,'CREATE','创建','D')");
        jdbc.update("INSERT INTO outbox_event (id,event_type,aggregate_type,aggregate_id,payload,event_status) VALUES (99108,'REVIEW_MAIL','REVIEW_TASK',99100,'{}','PENDING')");
        jdbc.update("INSERT INTO notification_send_record (outbox_event_id,task_id,event_type,template_code,delivery_status) VALUES (99108,99100,'REVIEW_MAIL','PCB_TASK_CREATED','FAILED')");
        jdbc.update("INSERT INTO task_archive_snapshot (task_id,file_snapshot) VALUES (99100,'[]')");
        var originalTask = taskMapper.findById(99100L);
        var originalOpinion = opinions.findById(99101L);

        tasks.deleteTask(99100L, "D");

        assertThat(jdbc.queryForObject("SELECT delete_status FROM review_task WHERE id=99100", Integer.class)).isEqualTo(1);
        for (String table : List.of("review_opinion", "review_submission", "review_file", "task_check_item", "task_flow_record", "notification_send_record", "task_archive_snapshot")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=99100 AND delete_status=1", Integer.class)).as(table).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT delete_status FROM opinion_reply WHERE id=99102", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delete_status FROM opinion_confirmation WHERE id=99103", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delete_status FROM outbox_event WHERE id=99108", Integer.class)).isEqualTo(1);
        assertThat(taskMapper.findById(99100L)).isNull();
        assertThat(taskMapper.countPage(null, null, null, null, null, null, 99100L)).isZero();
        assertThat(taskMapper.findAll()).noneMatch(task -> task.getId() == 99100L);
        assertThat(taskMapper.findAllUnfinished()).noneMatch(task -> task.getId() == 99100L);
        assertThat(opinions.findById(99101L)).isNull();
        assertThat(opinions.findByTaskId(99100L)).isEmpty();
        assertThat(opinions.findRepliesByOpinionId(99101L)).isEmpty();
        assertThat(opinions.findConfirmationsByOpinionId(99101L)).isEmpty();
        assertThat(opinions.findSubmissionsByTaskId(99100L)).isEmpty();
        assertThat(files.findById(99106L)).isNull();
        assertThat(files.findLatestByTaskId(99100L)).isEmpty();
        assertThat(checks.findByTaskId(99100L)).isEmpty();
        assertThat(flows.findByTaskId(99100L)).isEmpty();
        assertThat(outbox.findByAggregate("REVIEW_TASK", 99100L)).isEmpty();
        assertThat(outbox.findDispatchable(100, 3)).noneMatch(event -> event.getId() == 99108L);
        assertThat(notifications.findByTaskId(99100L)).isEmpty();
        assertThat(notifications.findByOutboxEventId(99108L)).isEmpty();
        assertThat(archives.existsByTaskId(99100L)).isFalse();
        assertThat(archives.findByTaskId(99100L)).isNull();
        assertThat(taskMapper.update(originalTask)).isZero();
        assertThat(taskMapper.updateBasicInfo(originalTask)).isZero();
        assertThat(taskMapper.deleteById(99100L)).isZero();
        assertThat(opinions.updateStatus(originalOpinion)).isZero();
        assertThat(files.markAsLatest(99100L, 99106L)).isZero();
        assertThat(checks.markConfirmedPass(99100L, 99104L)).isZero();
        assertThat(outbox.claimForDispatch(99108L)).isZero();
        assertThat(jdbc.queryForObject("SELECT delete_status FROM review_task WHERE id=99100", Integer.class)).isEqualTo(1);
    }

    @Test
    void whitelistRestoresTheSamePersonAndRoleRows() {
        var person = whitelist.findActiveByEmployeeNo("BMS003");
        whitelist.addOrRestoreRole("BMS003", "PCB_EXPERT", "D");
        Long roleId = jdbc.queryForObject("SELECT id FROM reviewer_whitelist_role WHERE employee_no='BMS003' AND review_role='PCB_EXPERT'", Long.class);
        assertThat(whitelist.logicDeleteById(person.getId(), "D")).isEqualTo(1);
        whitelist.logicDeleteRolesByEmployeeNo("BMS003", "D");
        assertThat(whitelist.findActiveByEmployeeNo("BMS003")).isNull();
        assertThat(whitelist.findByRoleAndEmployeeNo("PCB_EXPERT", "BMS003")).isNull();
        assertThat(whitelist.findAnyByEmployeeNo("BMS003").getDeleted()).isTrue();
        assertThat(whitelist.updateProfile(person)).isZero();
        assertThat(whitelist.logicDeleteById(person.getId(), "D")).isZero();
        assertThat(whitelist.restore(person)).isEqualTo(1);
        whitelist.addOrRestoreRole("BMS003", "PCB_EXPERT", "D");
        assertThat(whitelist.findActiveByEmployeeNo("BMS003").getId()).isEqualTo(person.getId());
        assertThat(whitelist.findByRoleAndEmployeeNo("PCB_EXPERT", "BMS003")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT id FROM reviewer_whitelist_role WHERE employee_no='BMS003' AND review_role='PCB_EXPERT'", Long.class)).isEqualTo(roleId);
    }

    @Test
    void repeatedSubmissionCreatesNewRowsWithoutRestoringDeletedRounds() {
        insertTask();
        for (int round = 0; round < 3; round++) {
            opinions.insertSubmission(99100L, OpinionSourceType.PCB_REVIEW, "R");
            assertThat(opinions.findSubmissionsByTaskId(99100L)).hasSize(1);
            assertThatThrownBy(() -> opinions.insertSubmission(99100L, OpinionSourceType.PCB_REVIEW, "R"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            assertThat(opinions.deleteSubmissions(99100L)).isEqualTo(1);
            assertThat(opinions.deleteSubmissions(99100L)).isZero();
        }
        opinions.insertSubmission(99100L, OpinionSourceType.PCB_REVIEW, "R");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_submission WHERE task_id=99100 AND delete_status=1", Integer.class)).isEqualTo(3);
        assertThat(opinions.findSubmissionsByTaskId(99100L)).hasSize(1);
    }

    @Test
    void deletedUnfilledSnapshotCanBeRecreatedWithANewId() {
        insertTask();
        var item = new TaskCheckItemRecord();
        item.setId(99104L); item.setTaskId(99100L); item.setItemId(99105L); item.setSortNo(1); item.setStatus("PENDING");
        checks.insert(item);
        assertThat(checks.deleteUnfilledSnapshot(99100L, 99104L)).isEqualTo(1);
        item.setId(99106L);
        checks.insert(item);
        assertThat(checks.findByTaskId(99100L)).extracting(TaskCheckItemRecord::getId).containsExactly(99106L);
        assertThat(jdbc.queryForObject("SELECT delete_status FROM task_check_item WHERE id=99104", Integer.class)).isEqualTo(1);
    }

    @Test
    void deletedTemplateRemainsReadableOnlyForExistingTaskSnapshots() {
        jdbc.update("INSERT INTO check_item_template (id,review_type,item_name) VALUES (99105,'PCB','历史检查项')");
        var template = templates.findById(99105L);
        assertThat(templates.disableById(99105L)).isEqualTo(1);
        assertThat(templates.findById(99105L)).isNull();
        assertThat(templates.findByIds(List.of(99105L))).isEmpty();
        assertThat(templates.findSnapshotById(99105L).getItemName()).isEqualTo("历史检查项");
        assertThat(templates.update(template)).isZero();
        assertThat(templates.disableById(99105L)).isZero();
    }

    private void insertTask() {
        jdbc.update("INSERT INTO review_task (id,review_type,task_name,project_name,designer_employee_no,design_name,task_status,initial_file_ids,version) VALUES (99100,'PCB','删除测试','BMS','D','PCB','PCB_EXPERT_REVIEWING','',0)");
    }
}
