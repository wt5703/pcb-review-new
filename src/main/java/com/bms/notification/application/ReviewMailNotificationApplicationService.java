package com.bms.notification.application;

import com.bms.file.domain.FileCategory;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.notification.domain.MailMessage;
import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import com.bms.review.domain.ReviewRole;
import com.bms.review.infrastructure.ReviewerWhitelistMapper;
import com.bms.review.infrastructure.ReviewerWhitelistRecord;
import com.bms.task.domain.ReviewTask;
import com.bms.task.domain.ReviewType;
import com.bms.task.domain.TaskReviewerAssignment;
import com.bms.task.domain.TaskReviewerAssignmentCodec;
import com.bms.task.infrastructure.ReviewTaskRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-28
 * @description 根据任务真实分配人员组装评审邮件，并将待发送邮件写入 Outbox。此类不直接投递邮件，邮件异常不会影响任务主流程。
 */
@Service
public class ReviewMailNotificationApplicationService {
    public static final String REVIEW_MAIL_EVENT = "REVIEW_MAIL";
    private static final Logger log = LoggerFactory.getLogger(ReviewMailNotificationApplicationService.class);

    private final OutboxEventMapper outboxEventMapper;
    private final ReviewFileMapper fileMapper;
    private final ReviewerWhitelistMapper whitelistMapper;
    private final ObjectMapper objectMapper;
    private final MailTemplateService mailTemplateService;

    public ReviewMailNotificationApplicationService(OutboxEventMapper outboxEventMapper, ReviewFileMapper fileMapper,
                                                    ReviewerWhitelistMapper whitelistMapper, ObjectMapper objectMapper,
                                                    MailTemplateService mailTemplateService) {
        this.outboxEventMapper = outboxEventMapper;
        this.fileMapper = fileMapper;
        this.whitelistMapper = whitelistMapper;
        this.objectMapper = objectMapper;
        this.mailTemplateService = mailTemplateService;
    }

    /** 任务提交后通知 PCB 的硬件/EMC 专家，或通知原理图任务的实际分配专家。 */
    public void enqueueTaskCreated(ReviewTask task) {
        List<TaskReviewerAssignment> assignments = assignments(task.reviewerAssignments(), task.expertLeaderEmployeeNo(), task.reviewRoles());
        if (task.reviewType() == ReviewType.PCB) {
            List<TaskReviewerAssignment> receivers = filter(assignments, assignment -> assignment.reviewRole() == ReviewRole.HARDWARE_EXPERT
                    || assignment.reviewRole() == ReviewRole.EMC_EXPERT);
            enqueue(task.id(), ReviewMailType.PCB_TASK_CREATED, task.projectName() + " PCB布局布线评审", receivers,
                    initialAttachments(task.initialFileIds(), FileCategory.PCB_REVIEW), Map.of("projectName", task.projectName()));
            return;
        }
        enqueue(task.id(), ReviewMailType.SCHEMATIC_TASK_CREATED, task.projectName() + "原理图评审", assignments,
                initialAttachments(task.initialFileIds(), FileCategory.SCHEMATIC_REVIEW), Map.of("projectName", task.projectName()));
    }

    /** PCB 设计者开启工艺和/或结构评审后，仅通知本次实际开启阶段的评审人员。 */
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
        String reviewName = hasProcess && hasStructure ? "工艺评审与结构评审" : hasProcess ? "工艺评审" : "结构评审";
        enqueue(task.getId(), ReviewMailType.PCB_OPTIONAL_REVIEW, task.getProjectName() + reviewName, receivers,
                latestAttachments(task.getId(), categories), Map.of("projectName", task.getProjectName(), "reviewName", reviewName));
    }

    /** 任务结束后发送当前任务中实际分配人员所需的终版文件。 */
    public void enqueueTaskFinished(ReviewTaskRecord task) {
        ReviewType reviewType = ReviewType.valueOf(task.getReviewType());
        if (reviewType == ReviewType.PCB) {
            List<TaskReviewerAssignment> hardwareReceivers = filter(assignments(task), assignment -> assignment.reviewRole() == ReviewRole.HARDWARE_EXPERT);
            enqueue(task.getId(), ReviewMailType.PCB_TASK_FINISHED, task.getProjectName() + "PCB终版图", hardwareReceivers,
                    latestAttachments(task.getId(), List.of(FileCategory.PCB_REVIEW, FileCategory.PCB_PROCESS_REVIEW,
                            FileCategory.PCB_STRUCTURE_REVIEW)), Map.of("projectName", task.getProjectName()));
            return;
        }
        enqueue(task.getId(), ReviewMailType.SCHEMATIC_TASK_FINISHED, task.getProjectName() + "原理图终版", assignments(task),
                latestAttachments(task.getId(), List.of(FileCategory.SCHEMATIC_REVIEW)), Map.of("projectName", task.getProjectName()));
    }

    private void enqueue(long taskId, ReviewMailType type, String subject, List<TaskReviewerAssignment> assignments,
                         List<MailMessage.MailAttachment> attachments, Map<String, String> variables) {
        List<MailMessage.MailRecipient> recipients = recipients(assignments);
        if (recipients.isEmpty()) {
            // 保留失败投递意图，由异步消费者写入失败记录；不能让已成功的任务创建或流程推进回滚。
            log.error("邮件收件人资料不完整：taskId={}, mailType={}，将记录为一次失败投递", taskId, type);
        }
        Map<String, String> templateVariables = new LinkedHashMap<>(variables);
        templateVariables.put("recipientNames", recipientNames(recipients));
        String content = mailTemplateService.render(type, templateVariables);
        MailMessage message = new MailMessage(type.name(), subject, content, recipients, carbonCopies(recipients), attachments);
        try {
            outboxEventMapper.insert(new OutboxEventRecord(REVIEW_MAIL_EVENT, "REVIEW_TASK", taskId,
                    objectMapper.writeValueAsString(message), "PENDING"));
        } catch (JsonProcessingException | RuntimeException exception) {
            // Outbox 写入失败同样不能破坏已完成的核心业务；运维日志保留排查线索。
            log.error("写入邮件 Outbox 失败：taskId={}, mailType={}, subject={}", taskId, type, subject, exception);
        }
    }

    /** CC 为硬件开发人员，排除任何工艺/结构专家及已在 To 中的人员。 */
    private List<MailMessage.MailRecipient> carbonCopies(List<MailMessage.MailRecipient> recipients) {
        Set<String> toEmails = recipients.stream().map(MailMessage.MailRecipient::email).map(this::emailKey).collect(Collectors.toSet());
        Map<String, WhitelistPerson> people = new LinkedHashMap<>();
        List<ReviewerWhitelistRecord> whitelist = whitelistMapper.findAll();
        for (ReviewerWhitelistRecord record : whitelist == null ? List.<ReviewerWhitelistRecord>of() : whitelist) {
            if (record.getEmployeeNo() == null || record.getEmployeeNo().isBlank()) {
                continue;
            }
            WhitelistPerson person = people.computeIfAbsent(record.getEmployeeNo(), ignored -> new WhitelistPerson(record));
            person.roles.add(record.getReviewRole());
        }
        return people.values().stream()
                .filter(WhitelistPerson::isHardwareDeveloper)
                .filter(person -> !person.hasOptionalReviewRole())
                .map(WhitelistPerson::toRecipient)
                .filter(recipient -> recipient.email() != null && !recipient.email().isBlank())
                .filter(recipient -> !toEmails.contains(emailKey(recipient.email())))
                .collect(Collectors.toMap(recipient -> emailKey(recipient.email()), recipient -> recipient,
                        (first, ignored) -> first, LinkedHashMap::new))
                .values().stream().toList();
    }

    private List<TaskReviewerAssignment> assignments(ReviewTaskRecord task) {
        return assignments(TaskReviewerAssignmentCodec.decode(task.getReviewerAssignments()), task.getExpertLeaderEmployeeNo(), parseRoles(task.getReviewRoles()));
    }

    private List<TaskReviewerAssignment> assignments(List<TaskReviewerAssignment> assignments, String fallbackEmployeeNo, List<ReviewRole> roles) {
        if (assignments != null && !assignments.isEmpty()) {
            return assignments;
        }
        if (fallbackEmployeeNo == null || fallbackEmployeeNo.isBlank()) {
            return List.of();
        }
        ReviewRole fallbackRole = roles.isEmpty() ? ReviewRole.HARDWARE_EXPERT : roles.get(0);
        return List.of(new TaskReviewerAssignment(fallbackRole, List.of(fallbackEmployeeNo)));
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
        Map<String, MailMessage.MailRecipient> recipients = new LinkedHashMap<>();
        assignments.stream().flatMap(item -> item.reviewerEmployeeNos().stream()).distinct().forEach(employeeNo -> {
            ReviewerWhitelistRecord person = whitelistMapper.findActiveByEmployeeNo(employeeNo);
            if (person != null && person.getEmail() != null && !person.getEmail().isBlank()) {
                recipients.putIfAbsent(emailKey(person.getEmail()), new MailMessage.MailRecipient(person.getDisplayName(), person.getEmail()));
            }
        });
        return List.copyOf(recipients.values());
    }

    private String recipientNames(List<MailMessage.MailRecipient> recipients) {
        String names = recipients.stream().map(MailMessage.MailRecipient::name)
                .filter(name -> name != null && !name.isBlank()).collect(Collectors.joining("、"));
        return names.isBlank() ? "各位专家" : names;
    }

    private List<MailMessage.MailAttachment> initialAttachments(List<Long> initialFileIds, FileCategory category) {
        return initialFileIds.stream().map(fileMapper::findById).filter(java.util.Objects::nonNull)
                .filter(file -> category.name().equals(file.getFileCategory())).map(this::toAttachment).toList();
    }

    private List<MailMessage.MailAttachment> latestAttachments(long taskId, Collection<FileCategory> categories) {
        Map<String, MailMessage.MailAttachment> attachments = new LinkedHashMap<>();
        categories.stream().flatMap(category -> fileMapper.findLatestByTaskIdAndCategory(taskId, category.name()).stream())
                .map(this::toAttachment).forEach(attachment -> attachments.putIfAbsent(attachment.fileId(), attachment));
        return List.copyOf(attachments.values());
    }

    private MailMessage.MailAttachment toAttachment(ReviewFileRecord file) {
        return new MailMessage.MailAttachment(file.getFileId(), file.getFileName(), file.getFileCategory(), file.getResourcePath());
    }

    private String emailKey(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }

    private static final class WhitelistPerson {
        private final ReviewerWhitelistRecord record;
        private final Set<String> roles = new LinkedHashSet<>();

        private WhitelistPerson(ReviewerWhitelistRecord record) { this.record = record; }
        private boolean isHardwareDeveloper() {
            return record.getDepartmentName() != null && record.getDepartmentName().contains("硬件");
        }
        private boolean hasOptionalReviewRole() {
            return roles.contains(ReviewRole.PROCESS_EXPERT.name()) || roles.contains(ReviewRole.STRUCTURE_EXPERT.name());
        }
        private MailMessage.MailRecipient toRecipient() { return new MailMessage.MailRecipient(record.getDisplayName(), record.getEmail()); }
    }
}
