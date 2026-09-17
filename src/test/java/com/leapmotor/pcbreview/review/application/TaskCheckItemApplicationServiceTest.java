package com.leapmotor.pcbreview.review.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.application.TaskNodeAuthorizationService;
import com.leapmotor.pcbreview.identity.domain.Role;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.review.domain.CheckResult;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemTemplateMapper;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemTemplateRecord;
import com.leapmotor.pcbreview.review.infrastructure.TaskCheckItemMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskCheckItemRecord;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionMapper;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileMapper;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemAttachmentMapper;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
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
    private final ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
    private final CheckItemAttachmentMapper attachmentMapper = mock(CheckItemAttachmentMapper.class);
    private final TaskCheckItemApplicationService service = new TaskCheckItemApplicationService(taskMapper, templateMapper,
            taskCheckItemMapper, assignmentAccessMapper, taskNodeAuthorizationService, opinionMapper, fileMapper, attachmentMapper);
    private final CurrentUser pcbLeader = new CurrentUser(1L, Set.of(Role.PCB_LEADER));

    @Test
    void shouldMaterializeCurrentTemplateForActiveTask() {
        ReviewTaskRecord task = task(TaskStatus.MUTUAL_REVIEWING);
        CheckItemTemplateRecord template = template(31L, "spacing", "线距检查");
        TaskCheckItemRecord materialized = item(51L, 31L, "spacing", "线距检查");
        when(taskMapper.findById(1001L)).thenReturn(task);
        when(templateMapper.findEnabledByReviewType(ReviewType.PCB.name())).thenReturn(List.of(template));
        when(taskCheckItemMapper.findByTaskId(1001L)).thenReturn(List.of(), List.of(materialized));
        when(taskCheckItemMapper.nextId()).thenReturn(51L);

        List<TaskCheckItemApplicationService.CheckItemView> items = service.list(1001L, pcbLeader);

        assertThat(items).extracting(TaskCheckItemApplicationService.CheckItemView::itemName).containsExactly("线距检查");
        verify(taskCheckItemMapper).insert(any(TaskCheckItemRecord.class));
    }

    @Test
    void shouldRejectFailedCheckItemWithoutLinkedOpinion() {
        ReviewTaskRecord task = task(TaskStatus.MUTUAL_REVIEWING);
        when(taskMapper.findById(1001L)).thenReturn(task);
        when(templateMapper.findEnabledByReviewType(ReviewType.PCB.name())).thenReturn(List.of());
        when(taskCheckItemMapper.findByTaskId(1001L)).thenReturn(List.of());
        when(taskCheckItemMapper.findByTaskIdAndId(1001L, 51L)).thenReturn(item(51L, 31L, "spacing", "线距检查"));

        assertThatThrownBy(() -> service.submit(1001L, 51L,
                new TaskCheckItemApplicationService.SubmitCheckItemCommand(CheckResult.FAIL, "线距不足", null, 0L), pcbLeader))
                .isInstanceOf(BusinessException.class)
                .hasMessage("不合格检查项必须关联评审意见");
    }

    @Test
    void shouldKeepExistingSnapshotWhenTaskIsFinished() {
        ReviewTaskRecord task = task(TaskStatus.FINISHED);
        TaskCheckItemRecord snapshot = item(51L, 31L, "spacing", "结束时的线距检查");
        when(taskMapper.findById(1001L)).thenReturn(task);
        when(taskCheckItemMapper.findByTaskId(1001L)).thenReturn(List.of(snapshot));

        List<TaskCheckItemApplicationService.CheckItemView> items = service.list(1001L, pcbLeader);

        assertThat(items).extracting(TaskCheckItemApplicationService.CheckItemView::itemName).containsExactly("结束时的线距检查");
        verify(templateMapper, never()).findEnabledByReviewType(ReviewType.PCB.name());
    }

    private ReviewTaskRecord task(TaskStatus status) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(1001L);
        task.setReviewType(ReviewType.PCB.name());
        task.setStatus(status.name());
        return task;
    }

    private CheckItemTemplateRecord template(long id, String key, String name) {
        CheckItemTemplateRecord record = new CheckItemTemplateRecord();
        record.setId(id);
        record.setItemKey(key);
        record.setItemName(name);
        record.setSortNo(1);
        return record;
    }

    private TaskCheckItemRecord item(long id, long templateId, String key, String name) {
        TaskCheckItemRecord record = new TaskCheckItemRecord();
        record.setId(id);
        record.setTemplateItemId(templateId);
        record.setTemplateItemKey(key);
        record.setItemName(name);
        record.setSortNo(1);
        record.setStatus("PENDING");
        record.setVersion(0L);
        return record;
    }
}
