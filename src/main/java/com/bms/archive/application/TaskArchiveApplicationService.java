package com.bms.archive.application;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bms.archive.infrastructure.TaskArchiveSnapshotMapper;
import com.bms.archive.infrastructure.TaskArchiveSnapshotRecord;
import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 在任务结束事务内冻结可展示的归档记录，覆盖流程节点和各阶段最新文件，确保后续业务明细变化不会影响历史回溯。
 */
@Service
public class TaskArchiveApplicationService {
    private final TaskArchiveSnapshotMapper snapshotMapper;
    private final ReviewFileMapper fileMapper;
    private final TaskReviewerMapper reviewerMapper;
    private final TaskFlowMapper flowMapper;
    private final ObjectMapper objectMapper;

    public TaskArchiveApplicationService(TaskArchiveSnapshotMapper snapshotMapper, ReviewFileMapper fileMapper,
                                         TaskReviewerMapper reviewerMapper,
                                         TaskFlowMapper flowMapper,
                                         ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.fileMapper = fileMapper;
        this.reviewerMapper = reviewerMapper;
        this.flowMapper = flowMapper;
        this.objectMapper = objectMapper;
    }

    public void archive(ReviewTaskRecord task) {
        if (snapshotMapper.existsByTaskId(task.getId())) {
            return;
        }
        long taskId = task.getId();
        List<StageFileView> stageFiles = fileMapper.findLatestByTaskId(taskId).stream().map(this::toStageFile).toList();
        List<ReviewerView> reviewers = reviewerMapper.findActiveByTaskId(taskId).stream().map(this::toReviewer).toList();
        List<FlowNodeView> flowNodes = flowMapper.findByTaskId(taskId).stream().map(this::toFlowNode).toList();
        snapshotMapper.insert(new TaskArchiveSnapshotRecord(taskId, task.getStatus(),
                json(new TaskSnapshot(taskId, task.getTaskName(), task.getStatus())), json(stageFiles), json(reviewers),
                json(List.of()), json(flowNodes), json(List.of())));
    }

    public ArchiveView get(long taskId) {
        TaskArchiveSnapshotRecord snapshot = snapshotMapper.findByTaskId(taskId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务尚未结束归档");
        }
        return new ArchiveView(readList(snapshot.flowSnapshot(), FlowNodeView.class),
                readList(snapshot.fileSnapshot(), StageFileView.class));
    }

    private StageFileView toStageFile(ReviewFileRecord file) {
        return new StageFileView(file.getId(), stageNameForFile(file), file.getFileCategory(), file.getBusinessFileKey(),
                file.getFileName(), file.getUploadedBy(), displayName(file.getUploadedBy()), file.getUploadedAt(),
                file.getResourcePath(), file.getFileFormat(), file.getFileSize(), file.getMd5(),
                "/leapmotor/pcb_review/files/download?taskId=" + file.getTaskId() + "&fileId=" + file.getId());
    }

    private ReviewerView toReviewer(TaskReviewerRecord reviewer) {
        return new ReviewerView(reviewer.getReviewerId(), displayName(reviewer.getReviewerId()), reviewer.getReviewRole(), reviewer.getProcessStatus());
    }

    private FlowNodeView toFlowNode(TaskFlowRecord flow) {
        return new FlowNodeView(flow.getCreatedAt(), stageNameForStatus(flow.getToStatus()), displayName(flow.getOperatorId()), flow.getComment());
    }

    private String stageNameForFileCategory(String category) {
        return switch (category) {
            case "TASK_CREATION" -> "任务创建";
            case "PCB_REVIEW" -> "PCB评审";
            case "SCHEMATIC_REVIEW" -> "原理图评审";
            case "PROCESS_REVIEW" -> "工艺评审";
            case "STRUCTURE_REVIEW" -> "结构评审";
            case "MUTUAL_CHECK_REVIEW" -> "互检单评审";
            default -> category;
        };
    }

    private String stageNameForFile(ReviewFileRecord file) {
        return file.getUploadedStage() == null || file.getUploadedStage().isBlank()
                ? stageNameForFileCategory(file.getFileCategory()) : stageNameForStatus(file.getUploadedStage());
    }

    private String stageNameForStatus(String status) {
        try {
            return TaskStatus.valueOf(status).displayName();
        } catch (IllegalArgumentException ignored) {
            return status;
        }
    }

    private String displayName(Long userId) {
        return userId == null ? null : "用户#" + userId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, "归档快照序列化失败");
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "归档快照格式无法读取");
        }
    }

    private <T> List<T> readList(String value, Class<T> elementType) {
        try {
            JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "归档快照格式无法读取");
        }
    }

    public record ArchiveView(List<FlowNodeView> flowNodes, List<StageFileView> stageFiles) {
    }

    public record TaskSnapshot(long taskId, String taskName, String status) {
    }

    public record StageFileView(Long fileId, String stageName, String fileCategory, String businessFileKey, String fileName,
                                Long uploaderId, String uploaderName, LocalDateTime uploadedAt,
                                String resourcePath, String fileFormat, Long fileSize, String md5, String downloadPath) {
    }

    public record ReviewerView(Long reviewerId, String reviewerName, String reviewRole, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlowNodeView(LocalDateTime occurredAt, String stageName, String operatorName,
                               @JsonAlias("comment") String content) {
    }

}
