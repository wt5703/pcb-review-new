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
import com.bms.notification.infrastructure.NotificationSendRecord;
import com.bms.notification.infrastructure.NotificationSendRecordMapper;
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
 * @description 在任务结束事务内冻结阶段文件和邮件投递记录；流程节点统一从 task_flow_record 查询，避免重复维护流程快照。
 */
@Service
public class TaskArchiveApplicationService {
    private final TaskArchiveSnapshotMapper snapshotMapper;
    private final ReviewFileMapper fileMapper;
    private final TaskFlowMapper flowMapper;
    private final NotificationSendRecordMapper notificationSendRecordMapper;
    private final ObjectMapper objectMapper;

    public TaskArchiveApplicationService(TaskArchiveSnapshotMapper snapshotMapper, ReviewFileMapper fileMapper,
                                         TaskFlowMapper flowMapper,
                                         NotificationSendRecordMapper notificationSendRecordMapper,
                                         ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.fileMapper = fileMapper;
        this.flowMapper = flowMapper;
        this.notificationSendRecordMapper = notificationSendRecordMapper;
        this.objectMapper = objectMapper;
    }

    public void archive(ReviewTaskRecord task) {
        if (snapshotMapper.existsByTaskId(task.getId())) {
            return;
        }
        long taskId = task.getId();
        List<StageFileView> stageFiles = fileMapper.findLatestByTaskId(taskId).stream().map(this::toStageFile).toList();
        List<NotificationRecordView> notificationRecords = notificationSendRecordMapper.findByTaskId(taskId).stream()
                .map(NotificationRecordView::from).toList();
        snapshotMapper.insert(new TaskArchiveSnapshotRecord(taskId, json(stageFiles), json(notificationRecords)));
    }

    public ArchiveView get(long taskId) {
        TaskArchiveSnapshotRecord snapshot = snapshotMapper.findByTaskId(taskId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务尚未结束归档");
        }
        return new ArchiveView(flowMapper.findByTaskId(taskId).stream().map(this::toFlowNode).toList(),
                readList(snapshot.fileSnapshot(), StageFileView.class));
    }

    private StageFileView toStageFile(ReviewFileRecord file) {
        return new StageFileView(file.getId(), stageNameForFile(file), file.getFileCategory(), file.getFileName(),
                file.getUploadedBy(), displayName(file.getUploadedBy()), file.getUploadedAt(),
                file.getResourcePath(), file.getFileFormat(), file.getFileSize(), file.getMd5(),
                "/leapmotor/pcb_review/files/download?taskId=" + file.getTaskId() + "&fileId=" + file.getId());
    }

    private FlowNodeView toFlowNode(TaskFlowRecord flow) {
        return new FlowNodeView(flow.getCreatedAt(), flow.getActionName(), displayName(flow.getOperateId()), flow.getComment());
    }

    private String stageNameForFileCategory(String category) {
        return switch (category) {
            case "PCB_REVIEW" -> "PCB评审";
            case "SCHEMATIC_REVIEW" -> "原理图评审";
            case "PCB_PROCESS_REVIEW" -> "PCB工艺评审";
            case "PCB_STRUCTURE_REVIEW" -> "PCB结构评审";
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

    public record StageFileView(Long fileId, String stageName, String fileCategory, String fileName,
                                Long uploaderId, String uploaderName, LocalDateTime uploadedAt,
                                String resourcePath, String fileFormat, Long fileSize, String md5, String downloadPath) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlowNodeView(LocalDateTime occurredAt, String stageName, String operatorName,
                               @JsonAlias("comment") String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotificationRecordView(String eventType, String recipient, String templateCode,
                                         String deliveryStatus, String failureReason, LocalDateTime attemptedAt) {
        static NotificationRecordView from(NotificationSendRecord record) {
            return new NotificationRecordView(record.eventType(), record.recipient(), record.templateCode(),
                    record.deliveryStatus(), record.failureReason(), record.attemptedAt());
        }
    }

}
