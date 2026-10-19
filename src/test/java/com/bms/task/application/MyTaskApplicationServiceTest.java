package com.bms.task.application;

import com.bms.task.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.application.ReviewerWhitelistApplicationService;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.task.domain.MyTaskAction;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 验证“我的任务”只聚合当前节点需要用户操作的评审、答复、确认、分配和结束事项，不返回用户仅可查看的任务。
 */
class MyTaskApplicationServiceTest {
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final TaskAssignmentAccessMapper assignmentAccessMapper = mock(TaskAssignmentAccessMapper.class);
    private final ReviewOpinionMapper opinionMapper = mock(ReviewOpinionMapper.class);
    private final ReviewerWhitelistApplicationService reviewerWhitelistApplicationService = mock(ReviewerWhitelistApplicationService.class);
    private final MyTaskApplicationService service = new MyTaskApplicationService(taskMapper, assignmentAccessMapper, opinionMapper,
            reviewerWhitelistApplicationService);

    @Test
    void shouldOnlyReturnTaskRequiringCurrentReviewerAction() {
        when(taskMapper.findAllUnfinished()).thenReturn(List.of(task(1L, "BMS010", TaskStatus.PCB_EXPERT_REVIEWING), task(2L, "BMS010", TaskStatus.MUTUAL_CHECK_REVIEWING)));
        when(assignmentAccessMapper.isCurrentTaskProcessor(1L, "BMS088")).thenReturn(false);
        when(assignmentAccessMapper.isCurrentTaskProcessor(2L, "BMS088")).thenReturn(true);

        List<MyTaskApplicationService.MyTaskView> tasks = service.list("BMS088", "BMS088");

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).id()).isEqualTo(2L);
        assertThat(tasks.get(0).actions()).containsExactly(MyTaskAction.REVIEW);
    }

    @Test
    void shouldIncludeDesignerReplyAndLeaderFinishActions() {
        when(taskMapper.findAllUnfinished()).thenReturn(
                List.of(task(1L, "BMS010", TaskStatus.MUTUAL_CHECK_REVIEWING), task(2L, "BMS099", TaskStatus.MUTUAL_CHECK_REVIEWING)),
                List.of(task(2L, "BMS099", TaskStatus.PENDING_FINISH)));
        when(opinionMapper.findPendingReplyTaskIdsForDesignerEmployeeNo("BMS010")).thenReturn(List.of(1L));

        List<MyTaskApplicationService.MyTaskView> designerTasks = service.list("BMS010", "BMS010");
        assertThat(designerTasks.get(0).actions()).contains(MyTaskAction.REPLY_OPINION);

        List<MyTaskApplicationService.MyTaskView> leaderTasks = service.list("BMS001", "BMS001");
        assertThat(leaderTasks).extracting(MyTaskApplicationService.MyTaskView::id).containsExactly(2L);
        assertThat(leaderTasks.get(0).actions()).containsExactly(MyTaskAction.FINISH_TASK);
    }

    private ReviewTaskRecord task(long id, String designerEmployeeNo, TaskStatus status) {
        ReviewTaskRecord task = new ReviewTaskRecord();
        task.setId(id);
        task.setReviewType(ReviewType.PCB.name());
        task.setTaskName("我的任务测试" + id);
        task.setDesignerEmployeeNo(designerEmployeeNo);
        task.setDesignerName(designerEmployeeNo);
        task.setExpertLeaderEmployeeNo("BMS001");
        task.setExpertLeaderName("BMS001");
        task.setStatus(status.name());
        return task;
    }
}
