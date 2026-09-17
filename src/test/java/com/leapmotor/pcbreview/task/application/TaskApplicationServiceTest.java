package com.leapmotor.pcbreview.task.application;

import com.leapmotor.pcbreview.audit.infrastructure.OperationAuditMapper;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Role;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventMapper;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 验证评审任务应用服务在创建、提交和分页筛选时协调持久化、审计与 Outbox 的行为，不依赖真实数据库。
 */
class TaskApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
    private final TaskAssignmentAccessMapper taskAssignmentAccessMapper = mock(TaskAssignmentAccessMapper.class);
    private final TaskApplicationService service = new TaskApplicationService(taskMapper, auditMapper, outboxEventMapper,
            taskAssignmentAccessMapper);
    private final CurrentUser designer = new CurrentUser(10L, Set.of(Role.DESIGNER));

    @Test
    void shouldCreateTaskAndAppendAuditRecord() {
        when(taskMapper.nextId()).thenReturn(101L);

        TaskApplicationService.TaskView result = service.create(new TaskApplicationService.CreateTaskCommand(
                ReviewType.PCB, "BMS PCB评审", "BMS", 10L, "BMS-P1", "BMU"), designer);

        assertThat(result.id()).isEqualTo(101L);
        verify(taskMapper).insert(any(ReviewTaskRecord.class));
        verify(auditMapper).insert(any());
    }

    @Test
    void shouldAppendOutboxEventWhenTaskSubmitted() {
        when(taskMapper.findById(101L)).thenReturn(record(101L, "BMS PCB评审", TaskStatus.DRAFT, 0L));
        when(taskMapper.update(any())).thenReturn(1);

        TaskApplicationService.TaskView result = service.submit(101L, List.of(3001L), designer);

        assertThat(result.status()).isEqualTo(TaskStatus.PCB_PENDING_REVIEW.name());
        verify(auditMapper).insert(any());
        verify(outboxEventMapper).insert(any());
    }

    @Test
    void shouldFilterAndPageVisibleTasks() {
        when(taskMapper.findAll()).thenReturn(List.of(
                record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L),
                record(2L, "VCU原理图评审", TaskStatus.SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT, 0L),
                record(3L, "BMS第二轮评审", TaskStatus.PCB_PENDING_REVIEW, 0L)));

        TaskApplicationService.TaskPage page = service.list(new TaskApplicationService.TaskQuery(
                "BMS", null, null, null, 1, 1), new CurrentUser(1L, Set.of(Role.HARDWARE_DEPARTMENT_MANAGER)));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(1L);
    }

    @Test
    void shouldOnlyReturnAssignedTasksToProcessExpert() {
        when(taskMapper.findAll()).thenReturn(List.of(
                record(1L, "BMS PCB评审", TaskStatus.DRAFT, 0L),
                record(2L, "VCU原理图评审", TaskStatus.SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT, 0L)));
        when(taskAssignmentAccessMapper.isAssignedToTask(1L, 88L)).thenReturn(false);
        when(taskAssignmentAccessMapper.isAssignedToTask(2L, 88L)).thenReturn(true);

        TaskApplicationService.TaskPage page = service.list(null, new CurrentUser(88L, Set.of(Role.PROCESS_EXPERT)));

        assertThat(page.items()).extracting(TaskApplicationService.TaskView::id).containsExactly(2L);
    }

    private ReviewTaskRecord record(long id, String name, TaskStatus status, long version) {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(id);
        record.setReviewType(id == 2L ? ReviewType.SCHEMATIC.name() : ReviewType.PCB.name());
        record.setTaskName(name);
        record.setProjectName(id == 2L ? "VCU" : "BMS");
        record.setDesignerId(10L);
        record.setDesignName("设计");
        record.setPcbType(id == 2L ? null : "BMU");
        record.setStatus(status.name());
        record.setInitialFileIds("");
        record.setVersion(version);
        return record;
    }
}
