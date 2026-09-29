package com.bms.notification.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.file.domain.FileCategory;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.review.infrastructure.ReviewerWhitelistMapper;
import com.bms.review.infrastructure.ReviewerWhitelistRecord;
import com.bms.notification.domain.MailMessage;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import com.bms.review.domain.ReviewRole;
import com.bms.task.domain.ReviewTask;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.domain.TaskReviewerAssignmentCodec;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 按评审流程节点组装业务邮件，并将完整邮件载荷写入 Outbox；不直接调用邮件网关，避免邮件服务异常影响任务主事务。
 */
@Service
public class ReviewMailNotificationApplicationService {
    public static final String REVIEW_MAIL_EVENT = "REVIEW_MAIL";
    private static final String HARDWARE_DEPARTMENT_NAME = "BMS硬件开发部";

    private final OutboxEventMapper outboxEventMapper;
    private final ReviewFileMapper fileMapper;
    private final ReviewerWhitelistMapper whitelistMapper;
    private final ObjectMapper objectMapper;
    private final String hardwareDepartmentMailbox;

    public ReviewMailNotificationApplicationService(OutboxEventMapper outboxEventMapper, ReviewFileMapper fileMapper,
                                                    ReviewerWhitelistMapper whitelistMapper, ObjectMapper objectMapper,
                                                    @Value("${notification.hardware-department-mail:bms-hardware-development@bms.example.com}") String hardwareDepartmentMailbox) {
        this.outboxEventMapper = outboxEventMapper;
        this.fileMapper = fileMapper;
        this.whitelistMapper = whitelistMapper;
        this.objectMapper = objectMapper;
        this.hardwareDepartmentMailbox = hardwareDepartmentMailbox;
    }

    /** 任务提交后通知首轮 PCB 专家或原理图专家。 */
    public void enqueueTaskCreated(ReviewTask task) {
        List<TaskReviewerAssignment> assignments = assignments(task.reviewerAssignments(), task.expertLeaderId(), task.reviewRoles());
        boolean pcb = task.reviewType() == ReviewType.PCB;
        List<TaskReviewerAssignment> receivers = filter(assignments, assignment -> !pcb
                || (assignment.reviewRole() != ReviewRole.PROCESS_EXPERT && assignment.reviewRole() != ReviewRole.STRUCTURE_EXPERT));
        if (receivers.isEmpty()) {
            return;
        }
        String reviewRoles = roleNames(receivers);
        String subject = pcb ? task.projectName() + task.designName() : task.projectName() + "原理图评审";
        String attachmentDescription = pcb ? task.projectName() + "PCB文件" : task.projectName() + "原理图评审";
        enqueue(task.id(), pcb ? "PCB_TASK_CREATED" : "SCHEMATIC_TASK_CREATED", subject,
                greeting(receivers) + "\n附件为" + attachmentDescription + "，请进行" + reviewRoles,
                recipients(receivers), initialAttachments(task.initialFileIds()));
    }

    /** PCB 设计者开启工艺和/或结构评审后，只通知本次实际开启阶段的对应专家。 */
    public void enqueuePcbStageReview(ReviewTaskRecord task, Collection<FileCategory> requestedCategories) {
        List<FileCategory> categories = requestedCategories.stream()
                .filter(category -> category == FileCategory.PCB_PROCESS_REVIEW || category == FileCategory.PCB_STRUCTURE_REVIEW)
                .distinct().toList();
        if (categories.isEmpty()) {
            return;
        }
        boolean hasProcess = categories.contains(FileCategory.PCB_PROCESS_REVIEW);
        boolean hasStructure = categories.contains(FileCategory.PCB_STRUCTURE_REVIEW);
        List<TaskReviewerAssignment> receivers = filter(assignments(task), assignment -> (hasProcess && assignment.reviewRole() == ReviewRole.PROCESS_EXPERT)
                || (hasStructure && assignment.reviewRole() == ReviewRole.STRUCTURE_EXPERT));
        if (receivers.isEmpty()) {
            return;
        }
        String reviewTypeName = hasProcess && hasStructure ? "工艺与结构评审" : hasProcess ? "工艺评审" : "结构评审";
        enqueue(task.getId(), "PCB_PROCESS_STRUCTURE_REVIEW", task.getProjectName() + reviewTypeName,
                greeting(receivers) + "\n附件为" + task.getProjectName() + reviewTypeName + "文件，请进行" + reviewTypeName,
                recipients(receivers), latestAttachments(task.getId(), categories));
    }

    /** 任务结束后向创建任务时选定的专家发送终版图。 */
    public void enqueueTaskFinished(ReviewTaskRecord task) {
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        boolean pcb = reviewType == ReviewType.PCB;
        List<TaskReviewerAssignment> assignments = assignments(task);
        List<TaskReviewerAssignment> receivers = filter(assignments, assignment -> !pcb
                || (assignment.reviewRole() != ReviewRole.PROCESS_EXPERT && assignment.reviewRole() != ReviewRole.STRUCTURE_EXPERT));
        if (receivers.isEmpty()) {
            return;
        }
        String titleSuffix = pcb ? "PCB终版图" : "原理图评审终版图";
        enqueue(task.getId(), pcb ? "PCB_TASK_FINISHED" : "SCHEMATIC_TASK_FINISHED", task.getProjectName() + titleSuffix,
                greeting(receivers) + "\n以下是" + task.getProjectName() + titleSuffix,
                recipients(receivers), latestAttachments(task.getId(), List.of(FileCategory.values())));
    }

    private void enqueue(long taskId, String notificationType, String subject, String content,
                         List<MailMessage.MailRecipient> recipients, List<MailMessage.MailAttachment> attachments) {
        if (recipients.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮件收件人不能为空，请检查所选评审专家的邮箱");
        }
        MailMessage message = new MailMessage(notificationType, subject, content, recipients,
                List.of(new MailMessage.MailRecipient(HARDWARE_DEPARTMENT_NAME, hardwareDepartmentMailbox)), attachments);
        try {
            outboxEventMapper.insert(new OutboxEventRecord(REVIEW_MAIL_EVENT, "REVIEW_TASK", taskId,
                    objectMapper.writeValueAsString(message), "PENDING"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成邮件通知载荷", exception);
        }
    }

    private List<TaskReviewerAssignment> assignments(ReviewTaskRecord task) {
        return assignments(TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments()), task.getExpertLeaderId(), parseRoles(task.getReviewRoles()));
    }

    private List<TaskReviewerAssignment> assignments(List<TaskReviewerAssignment> assignments, Long fallbackUserId, List<ReviewRole> roles) {
        if (assignments != null && !assignments.isEmpty()) {
            return assignments;
        }
        if (fallbackUserId == null) {
            return List.of();
        }
        ReviewRole fallbackRole = roles.isEmpty() ? ReviewRole.HARDWARE_EXPERT : roles.get(0);
        return List.of(new TaskReviewerAssignment(fallbackRole, List.of(fallbackUserId)));
    }

    private List<ReviewRole> parseRoles(String reviewRoles) {
        if (reviewRoles == null || reviewRoles.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(reviewRoles.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).map(ReviewRole::valueOf).toList();
    }

    private List<TaskReviewerAssignment> filter(List<TaskReviewerAssignment> source, Predicate<TaskReviewerAssignment> predicate) {
        return source.stream().filter(predicate).toList();
    }

    private List<MailMessage.MailRecipient> recipients(List<TaskReviewerAssignment> assignments) {
        Map<Long, ReviewerWhitelistRecord> accounts = new LinkedHashMap<>();
        assignments.stream().flatMap(item -> item.reviewerIds().stream()).distinct().forEach(userId -> {
            ReviewerWhitelistRecord account = whitelistMapper.findById(userId);
            if (account != null && account.getEmail() != null && !account.getEmail().isBlank()) {
                accounts.put(userId, account);
            }
        });
        return accounts.values().stream().map(account -> new MailMessage.MailRecipient(account.getDisplayName(), account.getEmail())).toList();
    }

    private String greeting(List<TaskReviewerAssignment> assignments) {
        Map<Long, ReviewerWhitelistRecord> accounts = new LinkedHashMap<>();
        assignments.stream().flatMap(item -> item.reviewerIds().stream()).distinct().forEach(userId -> {
            ReviewerWhitelistRecord account = whitelistMapper.findById(userId);
            if (account != null) {
                accounts.put(userId, account);
            }
        });
        String names = accounts.values().stream().map(ReviewerWhitelistRecord::getDisplayName)
                .filter(name -> name != null && !name.isBlank()).map(this::surnameWithTitle).collect(Collectors.joining("，"));
        return names.isBlank() ? "各位专家你们好" : names + "你们好";
    }

    private String surnameWithTitle(String displayName) {
        return displayName.substring(0, displayName.offsetByCodePoints(0, 1)) + "工";
    }

    private String roleNames(List<TaskReviewerAssignment> assignments) {
        return assignments.stream().map(TaskReviewerAssignment::reviewRole).distinct()
                .map(this::roleName).collect(Collectors.joining("、"));
    }

    private String roleName(ReviewRole role) {
        return role.displayName();
    }

    private List<MailMessage.MailAttachment> initialAttachments(List<Long> initialFileIds) {
        return initialFileIds.stream().map(fileMapper::findById).filter(java.util.Objects::nonNull)
                .map(this::toAttachment).toList();
    }

    private List<MailMessage.MailAttachment> latestAttachments(long taskId, Collection<FileCategory> categories) {
        return categories.stream().flatMap(category -> fileMapper.findLatestByTaskIdAndCategory(taskId, category.name()).stream())
                .map(this::toAttachment).toList();
    }

    private MailMessage.MailAttachment toAttachment(ReviewFileRecord file) {
        return new MailMessage.MailAttachment(file.getFileId(), file.getFileName(), file.getFileCategory(), file.getResourcePath());
    }
}
