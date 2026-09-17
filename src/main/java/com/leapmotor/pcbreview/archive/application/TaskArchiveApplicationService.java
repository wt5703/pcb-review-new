package com.leapmotor.pcbreview.archive.application;

import com.leapmotor.pcbreview.archive.infrastructure.TaskArchiveSnapshotMapper;
import com.leapmotor.pcbreview.archive.infrastructure.TaskArchiveSnapshotRecord;
import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileMapper;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileRecord;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventEntity;
import com.leapmotor.pcbreview.notification.infrastructure.OutboxEventMapper;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionMapper;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionRecord;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskReviewerRecord;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import com.leapmotor.pcbreview.workflow.infrastructure.TaskFlowMapper;
import com.leapmotor.pcbreview.workflow.infrastructure.TaskFlowRecord;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 在任务结束事务内将任务关联的最终状态汇总为不可变归档摘要，后续查询可依赖快照而非易变的业务明细表还原历史。
 */
@Service
public class TaskArchiveApplicationService {
    private final TaskArchiveSnapshotMapper snapshotMapper;
    private final ReviewFileMapper fileMapper;
    private final TaskReviewerMapper reviewerMapper;
    private final ReviewOpinionMapper opinionMapper;
    private final TaskFlowMapper flowMapper;
    private final OutboxEventMapper outboxEventMapper;

    public TaskArchiveApplicationService(TaskArchiveSnapshotMapper snapshotMapper, ReviewFileMapper fileMapper,
                                         TaskReviewerMapper reviewerMapper, ReviewOpinionMapper opinionMapper,
                                         TaskFlowMapper flowMapper, OutboxEventMapper outboxEventMapper) {
        this.snapshotMapper = snapshotMapper;
        this.fileMapper = fileMapper;
        this.reviewerMapper = reviewerMapper;
        this.opinionMapper = opinionMapper;
        this.flowMapper = flowMapper;
        this.outboxEventMapper = outboxEventMapper;
    }

    public void archive(ReviewTaskRecord task) {
        if (snapshotMapper.existsByTaskId(task.getId())) {
            return;
        }
        long taskId = task.getId();
        snapshotMapper.insert(new TaskArchiveSnapshotRecord(taskId, task.getStatus(),
                "{\"taskId\":" + taskId + ",\"taskName\":\"" + escape(task.getTaskName()) + "\",\"status\":\"" + task.getStatus() + "\"}",
                summarize(fileMapper.findLatestByTaskId(taskId), file -> "{\"id\":" + file.getId() + ",\"md5\":\"" + escape(file.getMd5()) + "\",\"version\":" + file.getVersionNo() + "}"),
                summarize(reviewerMapper.findActiveByTaskId(taskId), reviewer -> "{\"reviewerId\":" + reviewer.getReviewerId() + ",\"role\":\"" + reviewer.getReviewRole() + "\",\"status\":\"" + reviewer.getProcessStatus() + "\"}"),
                summarize(opinionMapper.findByTaskId(taskId), opinion -> "{\"id\":" + opinion.getId() + ",\"status\":\"" + opinion.getStatus() + "\"}"),
                summarize(flowMapper.findByTaskId(taskId), flow -> "{\"id\":" + flow.getId() + ",\"action\":\"" + flow.getAction() + "\",\"to\":\"" + flow.getToStatus() + "\"}"),
                summarize(outboxEventMapper.findByAggregate("REVIEW_TASK", taskId), event -> "{\"id\":" + event.getId() + ",\"eventType\":\"" + event.getEventType() + "\",\"status\":\"" + event.getStatus() + "\"}")));
    }

    public ArchiveView get(long taskId) {
        TaskArchiveSnapshotRecord snapshot = snapshotMapper.findByTaskId(taskId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务尚未结束归档");
        }
        return new ArchiveView(snapshot.taskId(), snapshot.finalStatus(), snapshot.taskSnapshot(), snapshot.fileSnapshot(),
                snapshot.reviewerSnapshot(), snapshot.opinionSnapshot(), snapshot.flowSnapshot(), snapshot.notificationSnapshot());
    }

    private <T> String summarize(List<T> values, Function<T, String> serializer) {
        return "[" + values.stream().map(serializer).reduce((left, right) -> left + "," + right).orElse("") + "]";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ArchiveView(Long taskId, String finalStatus, String taskSnapshot, String fileSnapshot,
                              String reviewerSnapshot, String opinionSnapshot, String flowSnapshot, String notificationSnapshot) {
    }
}
