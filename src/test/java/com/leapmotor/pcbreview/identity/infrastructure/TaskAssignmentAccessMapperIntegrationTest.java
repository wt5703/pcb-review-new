package com.leapmotor.pcbreview.identity.infrastructure;

import com.leapmotor.pcbreview.review.domain.ReviewerProcessStatus;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 验证受限专家的数据范围和当前节点判断仅基于有效人员关系，改派后取消的历史记录不会继续授予任务查看或处理权限。
 */
@SpringBootTest
class TaskAssignmentAccessMapperIntegrationTest {
    @Autowired
    private TaskAssignmentAccessMapper assignmentAccessMapper;
    @Autowired
    private TaskReviewerMapper reviewerMapper;

    @Test
    void shouldExcludeCancelledReviewerFromTaskScopeAndIncludePendingReviewer() {
        reviewerMapper.insert(reviewer(9101L, 9201L, ReviewerProcessStatus.CANCELLED));
        reviewerMapper.insert(reviewer(9102L, 9202L, ReviewerProcessStatus.PENDING));

        assertThat(assignmentAccessMapper.isAssignedToTask(9101L, 9201L)).isFalse();
        assertThat(assignmentAccessMapper.isCurrentTaskProcessor(9101L, 9201L)).isFalse();
        assertThat(assignmentAccessMapper.isAssignedToTask(9101L, 9202L)).isTrue();
        assertThat(assignmentAccessMapper.isCurrentTaskProcessor(9101L, 9202L)).isTrue();
    }

    private TaskReviewerRecord reviewer(long id, long reviewerId, ReviewerProcessStatus status) {
        TaskReviewerRecord record = new TaskReviewerRecord();
        record.setId(id);
        record.setTaskId(9101L);
        record.setReviewRole("PROCESS_EXPERT");
        record.setReviewerId(reviewerId);
        record.setProcessStatus(status.name());
        record.setAssignedBy(1L);
        record.setNoOpinion(false);
        record.setVersion(0L);
        return record;
    }
}
