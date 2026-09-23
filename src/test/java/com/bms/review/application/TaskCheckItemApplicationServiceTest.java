package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.TaskNodeAuthorizationService;
import com.bms.identity.domain.Role;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.domain.CheckResult;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
import com.bms.review.infrastructure.TaskCheckItemMapper;
import com.bms.review.infrastructure.TaskCheckItemRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 验证进行中任务按当前模板生成检查项快照、已结束任务不再同步模板，以及不合格检查项必须带说明和关联意见的约束。
 */
class TaskCheckItemApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final CheckItemTemplateMapper templateMapper = mock(CheckItemTemplateMapper.class);
    private final TaskCheckItemMapper taskCheckItemMapper = mock(TaskCheckItemMapper.class);
    private final TaskAssignmentAccessMapper assignmentAccessMapper = mock(TaskAssignmentAccessMapper.class);
    private final TaskNodeAuthorizationService taskNodeAuthorizationService = mock(TaskNodeAuthorizationService.class);
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final OperationAuditMapper auditMapper = mock(OperationAuditMapper.class);
    private final TaskCheckItemApplicationService service = new TaskCheckItemApplicationService(taskMapper, templateMapper,
            taskCheckItemMapper, assignmentAccessMapper, taskNodeAuthorizationService, opinionMapper, auditMapper);
    private final CurrentUser pcbLeader = new CurrentUser(1L, Set.of(Role.PCB_LEADER));

    @Test
    void shouldMaterializeCurrentTemplateForActiveTask() {
        ReviewTaskRecord task = task(TaskStatus.MUTUAL_CHECK_REVIEWING);
        CheckItemTemplateRecord template = template(31L, "线距检查");
        TaskCheckItemRecord materialized = item(51L, 31L, "spacing", "线距检查");
        materialized.setParentId(null);
        when(taskMapper.findById(1001L)).thenReturn(task);
        when(templateMapper.findEnabledByReviewType(ReviewType.PCB.name())).thenReturn(List.of(template));
        when(taskCheckItemMapper.findByTaskId(1001L)).thenReturn(List.of(), List.of(materialized));
        when(taskCheckItemMapper.nextId()).thenReturn(51L);

        List<TaskCheckItemApplicationService.CheckItemCategoryView> items = service.list(1001L, pcbLeader);

        assertThat(items).extracting(item -> item.category().itemName()).containsExactly("线距检查");
        verify(taskCheckItemMapper).insert(any(TaskCheckItemRecord.class));
    }

    @Test
    void shouldRejectFailedCheckItemWithoutComment() {
        ReviewTaskRecord task = task(TaskStatus.MUTUAL_CHECK_REVIEWING);
        when(taskMapper.findById(1001L)).thenReturn(task);
        when(templateMapper.findEnabledByReviewType(ReviewType.PCB.name())).thenReturn(List.of());
        when(taskCheckItemMapper.findByTaskId(1001L)).thenReturn(List.of());
        when(taskCheckItemMapper.findByTaskIdAndId(1001L, 51L)).thenReturn(item(51L, 31L, "spacing", "线距检查"));

        assertThatThrownBy(() -> service.submit(1001L, 51L,
                new TaskCheckItemApplicationService.SubmitCheckItemCommand(CheckResult.FAIL, null, null), pcbLeader))
                .isInstanceOf(BusinessException.class)
                .hasMessage("不合格或 NC 检查项必须填写意见");
    }

    @Test
    void shouldKeepExistingSnapshotWhenTaskIsFinished() {
        ReviewTaskRecord task = task(TaskStatus.FINISHED);
        TaskCheckItemRecord category = item(41L, 30L, "category", "结束时的类别");
        category.setParentId(null);
        TaskCheckItemRecord snapshot = item(51L, 31L, "spacing", "结束时的线距检查");
        when(taskMapper.findById(1001L)).thenReturn(task);
        when(taskCheckItemMapper.findByTaskId(1001L)).thenReturn(List.of(category, snapshot));

        List<TaskCheckItemApplicationService.CheckItemCategoryView> items = service.list(1001L, pcbLeader);

        assertThat(items).extracting(item -> item.category().itemName()).containsExactly("结束时的类别");
        assertThat(items.get(0).items()).extracting(TaskCheckItemApplicationService.CheckItemListItemView::itemName).containsExactly("结束时的线距检查");
        verify(templateMapper, never()).findEnabledByReviewType(ReviewType.PCB.name());
    }

    private ReviewTaskRecord task(TaskStatus status) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setReviewType(ReviewType.PCB.name());
        task.setStatus(status.name());
        return task;
    }

    private CheckItemTemplateRecord template(long id, String name) {
        CheckItemTemplateRecord record = new CheckItemTemplateRecord();
        record.setId(id);
        record.setItemName(name);
        record.setSortNo(1);
        return record;
    }

    private TaskCheckItemRecord item(long id, long templateId, String key, String name) {
        TaskCheckItemRecord record = new TaskCheckItemRecord();
        record.setId(id);
        record.setTemplateItemId(templateId);
        record.setParentId(41L);
        record.setItemName(name);
        record.setSortNo(1);
        record.setStatus("PENDING");
        record.setVersion(0L);
        return record;
    }
}
