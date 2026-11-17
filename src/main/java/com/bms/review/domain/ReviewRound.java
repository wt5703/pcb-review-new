package com.bms.review.domain;

import com.bms.task.domain.TaskReviewerAssignmentCodec;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import java.util.List;
import java.util.Set;

/** 退回专家评审后以重建的实时待办判断本轮是否提交，不用历史意见替代本轮确认。 */
public final class ReviewRound {
    private ReviewRound() { }
    public static Set<String> pendingPcbReviewers(ReviewTaskRecord task, List<TaskFlowRecord> records) {
        if (!TaskStatus.PCB_EXPERT_REVIEWING.name().equals(task.getStatus()) || records == null) return Set.of();
        boolean returned = records.stream().anyMatch(record -> "RETURN".equals(record.getAction())
                && TaskStatus.PCB_EXPERT_REVIEWING.name().equals(record.getToStatus()));
        return returned ? Set.copyOf(TaskReviewerAssignmentCodec.decodeReviewerEmployeeNos(task.getAssignedReviewerEmployeeNos())) : Set.of();
    }
}
