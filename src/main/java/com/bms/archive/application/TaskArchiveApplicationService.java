package com.bms.archive.application;

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
import com.bms.review.domain.OpinionSourceType;
import com.bms.review.infrastructure.OpinionReplyRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.review.infrastructure.TaskReviewerMapper;
import com.bms.review.infrastructure.TaskReviewerRecord;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.bms.workflow.infrastructure.TaskFlowMapper;
import com.bms.workflow.infrastructure.TaskFlowRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 在任务结束事务内冻结可展示的归档记录，覆盖流转意见、各阶段最新文件和实际邮件发送记录，确保后续业务明细变化不会影响历史回溯。
 */
@Service
public class TaskArchiveApplicationService {
    private final TaskArchiveSnapshotMapper snapshotMapper;
    private final ReviewFileMapper fileMapper;
    private final TaskReviewerMapper reviewerMapper;
    private final ReviewOpinionMapper opinionMapper;
    private final TaskFlowMapper flowMapper;
    private final NotificationSendRecordMapper sendRecordMapper;
    private final ObjectMapper objectMapper;

    public TaskArchiveApplicationService(TaskArchiveSnapshotMapper snapshotMapper, ReviewFileMapper fileMapper,
                                         TaskReviewerMapper reviewerMapper, ReviewOpinionMapper opinionMapper,
                                         TaskFlowMapper flowMapper, NotificationSendRecordMapper sendRecordMapper,
                                         ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.fileMapper = fileMapper;
        this.reviewerMapper = reviewerMapper;
        this.opinionMapper = opinionMapper;
        this.flowMapper = flowMapper;
        this.sendRecordMapper = sendRecordMapper;
        this.objectMapper = objectMapper;
    }

    public void archive(ReviewTaskRecord task) {
        if (snapshotMapper.existsByTaskId(task.getId())) {
            return;
        }
        long taskId = task.getId();
        List<StageFileView> stageFiles = fileMapper.findLatestByTaskId(taskId).stream().map(this::toStageFile).toList();
        List<FlowOpinionView> flowOpinions = buildFlowOpinions(taskId);
        List<ReviewerView> reviewers = reviewerMapper.findActiveByTaskId(taskId).stream().map(this::toReviewer).toList();
        List<FlowEventView> flowEvents = flowMapper.findByTaskId(taskId).stream().map(this::toFlowEvent).toList();
        List<MailRecordView> mailRecords = sendRecordMapper.findByTaskId(taskId).stream().map(this::toMailRecord).toList();
        snapshotMapper.insert(new TaskArchiveSnapshotRecord(taskId, task.getStatus(),
                json(new TaskSnapshot(taskId, task.getTaskName(), task.getStatus())), json(stageFiles), json(reviewers),
                json(flowOpinions), json(flowEvents), json(mailRecords)));
    }

    public ArchiveView get(long taskId) {
        TaskArchiveSnapshotRecord snapshot = snapshotMapper.findByTaskId(taskId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务尚未结束归档");
        }
        return new ArchiveView(snapshot.taskId(), snapshot.finalStatus(), read(snapshot.taskSnapshot(), TaskSnapshot.class),
                readList(snapshot.fileSnapshot(), StageFileView.class), readList(snapshot.reviewerSnapshot(), ReviewerView.class),
                readList(snapshot.opinionSnapshot(), FlowOpinionView.class), readList(snapshot.flowSnapshot(), FlowEventView.class),
                readList(snapshot.notificationSnapshot(), MailRecordView.class));
    }

    private List<FlowOpinionView> buildFlowOpinions(long taskId) {
        List<FlowOpinionView> values = new ArrayList<>();
        for (ReviewOpinionRecord opinion : opinionMapper.findByTaskId(taskId)) {
            List<DesignerReplyView> replies = opinionMapper.findRepliesByOpinionId(opinion.getId()).stream()
                    .map(this::toDesignerReply).toList();
            values.add(new FlowOpinionView(opinion.getCreatedAt(), stageNameForOpinion(opinion.getSourceType()), opinion.getRaisedBy(),
                    displayName(opinion.getRaisedBy()), opinion.getContent(), "REVIEW_OPINION", opinion.getId(), replies));
        }
        for (TaskFlowRecord flow : flowMapper.findByTaskId(taskId)) {
            if (flow.getComment() != null && !flow.getComment().isBlank()) {
                values.add(new FlowOpinionView(flow.getCreatedAt(), stageNameForStatus(flow.getToStatus()), flow.getOperatorId(),
                        displayName(flow.getOperatorId()), flow.getComment(), "FLOW_COMMENT", flow.getId(), List.of()));
            }
        }
        values.sort(Comparator.comparing(FlowOpinionView::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FlowOpinionView::sourceId));
        return values;
    }

    private StageFileView toStageFile(ReviewFileRecord file) {
        return new StageFileView(file.getId(), stageNameForFile(file), file.getFileCategory(), file.getBusinessFileKey(),
                file.getFileName(), file.getVersionNo(), file.getUploadedBy(), displayName(file.getUploadedBy()), file.getUploadedAt(),
                file.getCompanyFileId(), file.getFileSize(), file.getMd5(), "/api/v1/files/" + file.getId() + "/download");
    }

    private ReviewerView toReviewer(TaskReviewerRecord reviewer) {
        return new ReviewerView(reviewer.getReviewerId(), displayName(reviewer.getReviewerId()), reviewer.getReviewRole(), reviewer.getProcessStatus());
    }

    private FlowEventView toFlowEvent(TaskFlowRecord flow) {
        return new FlowEventView(flow.getId(), flow.getCreatedAt(), stageNameForStatus(flow.getToStatus()), flow.getAction(),
                flow.getFromStatus(), flow.getToStatus(), flow.getOperatorId(), displayName(flow.getOperatorId()), flow.getComment());
    }

    private DesignerReplyView toDesignerReply(OpinionReplyRecord reply) {
        return new DesignerReplyView(reply.getId(), reply.getReplyNo(), reply.getReplyType(), reply.getReason(), reply.getFileVersionId(),
                reply.getRepliedBy(), displayName(reply.getRepliedBy()), reply.getCreatedAt());
    }

    private MailRecordView toMailRecord(NotificationSendRecord record) {
        return new MailRecordView(record.attemptedAt(), scenario(record.templateCode(), record.eventType()), record.recipient(),
                deliveryStatus(record.deliveryStatus()), record.deliveryStatus(), record.failureReason(), record.outboxEventId());
    }

    private String stageNameForOpinion(String sourceType) {
        return switch (OpinionSourceType.valueOf(sourceType)) {
            case MUTUAL_CHECK_ITEM, MUTUAL_EXTRA -> "互检";
            case PROCESS_REVIEW -> "工艺评审";
            case STRUCTURE_REVIEW -> "结构评审";
            case EXPERT_REVIEW -> "专家评审";
        };
    }

    private String stageNameForFileCategory(String category) {
        return switch (category) {
            case "PCB_SCHEMATIC" -> "设计文件";
            case "PROCESS" -> "工艺评审";
            case "STRUCTURE" -> "结构评审";
            default -> category;
        };
    }

    private String stageNameForFile(ReviewFileRecord file) {
        return file.getUploadedStage() == null || file.getUploadedStage().isBlank()
                ? stageNameForFileCategory(file.getFileCategory()) : stageNameForStatus(file.getUploadedStage());
    }

    private String stageNameForStatus(String status) {
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "PCB_PENDING_REVIEW" -> "PCB 待评审";
            case "PCB_EXPERT_REVIEWING" -> "专家评审";
            case "PCB_OPTIONAL_REVIEWING" -> "工艺/结构评审";
            case "PENDING_MUTUAL_ASSIGNMENT", "SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT" -> "互检分配";
            case "MUTUAL_REVIEWING" -> "互检";
            case "SCHEMATIC_PENDING_REVIEW" -> "原理图待评审";
            case "HARDWARE_REVIEWING" -> "硬件评审";
            case "PENDING_FINISH_CONFIRMATION" -> "结束确认";
            case "FINISHED" -> "任务结束";
            default -> status;
        };
    }

    private String scenario(String templateCode, String eventType) {
        String event = templateCode == null || templateCode.isBlank() ? eventType : templateCode;
        return switch (event) {
            case "TASK_SUBMITTED" -> "评审任务提交通知";
            case "TASK_STATUS_CHANGED" -> "评审阶段流转通知";
            case "REVIEWERS_ASSIGNED" -> "评审人员分配通知";
            case "REVIEWERS_REASSIGNED" -> "评审人员改派通知";
            case "FILE_VERSION_REGISTERED" -> "设计文件版本上传通知";
            case "OPINION_RAISED" -> "专家意见待答复通知";
            case "OPINION_REPLIED" -> "设计者答复待确认通知";
            case "OPINION_CONFIRMED_PASS" -> "意见确认通过通知";
            case "OPINION_CONFIRMED_REJECTED" -> "意见确认不通过通知";
            default -> event;
        };
    }

    private String deliveryStatus(String status) {
        return "SUCCESS".equals(status) ? "已发送" : "FAILED".equals(status) ? "发送失败" : status;
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

    public record ArchiveView(Long taskId, String finalStatus, TaskSnapshot task, List<StageFileView> stageFiles,
                              List<ReviewerView> reviewers, List<FlowOpinionView> flowOpinions,
                              List<FlowEventView> flowEvents, List<MailRecordView> mailRecords) {
    }

    public record TaskSnapshot(long taskId, String taskName, String status) {
    }

    public record StageFileView(Long fileId, String stageName, String fileCategory, String businessFileKey, String fileName,
                                Integer versionNo, Long uploaderId, String uploaderName, LocalDateTime uploadedAt,
                                String companyFileId, Long fileSize, String md5, String downloadPath) {
    }

    public record ReviewerView(Long reviewerId, String reviewerName, String reviewRole, String status) {
    }

    public record FlowOpinionView(LocalDateTime occurredAt, String stageName, Long operatorId, String operatorName,
                                  String content, String source, Long sourceId, List<DesignerReplyView> designerReplies) {
    }

    public record DesignerReplyView(Long replyId, Integer replyNo, String replyType, String content, Long fileVersionId,
                                    Long designerId, String designerName, LocalDateTime repliedAt) {
    }

    public record FlowEventView(Long flowId, LocalDateTime occurredAt, String stageName, String action, String fromStatus,
                                String toStatus, Long operatorId, String operatorName, String comment) {
    }

    public record MailRecordView(LocalDateTime sentAt, String scenario, String recipient, String status,
                                 String deliveryStatus, String failureReason, Long outboxEventId) {
    }
}
