package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.audit.infrastructure.OperationAuditMapper;
import com.bms.audit.infrastructure.OperationAuditRecord;
import com.bms.file.infrastructure.ReviewFileMapper;
import com.bms.file.infrastructure.ReviewFileRecord;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.TaskNodeAuthorizationService;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.domain.CheckItemStatus;
import com.bms.review.domain.CheckResult;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
import com.bms.review.infrastructure.CheckItemAttachmentMapper;
import com.bms.review.infrastructure.CheckItemAttachmentRecord;
import com.bms.review.infrastructure.CheckItemAttachmentViewRecord;
import com.bms.review.infrastructure.ReviewOpinionMapper;
import com.bms.review.infrastructure.ReviewOpinionRecord;
import com.bms.review.infrastructure.TaskCheckItemMapper;
import com.bms.review.infrastructure.TaskCheckItemRecord;
import com.bms.task.domain.TaskStatus;
import com.bms.task.infrastructure.ReviewTaskMapper;
import com.bms.task.infrastructure.ReviewTaskRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 维护任务内固定互检检查项：进行中任务按最新模板同步显示，已结束任务仅读取既有快照，并校验检查结论的说明与意见关联要求。
 */
@Service
public class TaskCheckItemApplicationService {
    private final ReviewTaskMapper taskMapper;
    private final CheckItemTemplateMapper templateMapper;
    private final TaskCheckItemMapper taskCheckItemMapper;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final TaskNodeAuthorizationService taskNodeAuthorizationService;
    private final ReviewOpinionMapper opinionMapper;
    private final ReviewFileMapper fileMapper;
    private final CheckItemAttachmentMapper attachmentMapper;
    private final OperationAuditMapper auditMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public TaskCheckItemApplicationService(ReviewTaskMapper taskMapper, CheckItemTemplateMapper templateMapper,
                                           TaskCheckItemMapper taskCheckItemMapper, TaskAssignmentAccessMapper assignmentAccessMapper,
                                           TaskNodeAuthorizationService taskNodeAuthorizationService, ReviewOpinionMapper opinionMapper,
                                           ReviewFileMapper fileMapper, CheckItemAttachmentMapper attachmentMapper,
                                           OperationAuditMapper auditMapper) {
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.taskNodeAuthorizationService = taskNodeAuthorizationService;
        this.opinionMapper = opinionMapper;
        this.fileMapper = fileMapper;
        this.attachmentMapper = attachmentMapper;
        this.auditMapper = auditMapper;
    }

    @Transactional
    public List<CheckItemView> list(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord task = requireVisibleTask(taskId, currentUser);
        List<CheckItemTemplateRecord> templates = synchronizeIfActive(task);
        List<TaskCheckItemRecord> records = taskCheckItemMapper.findByTaskId(taskId);
        if (!isFinished(task)) {
            Set<Long> activeTemplateIds = templates.stream().map(CheckItemTemplateRecord::getId).collect(java.util.stream.Collectors.toSet());
            records = records.stream().filter(record -> activeTemplateIds.contains(record.getTemplateItemId())).toList();
        }
        Map<String, String> categoryNames = isFinished(task) ? new HashMap<>() : templateMapper.findEnabledByReviewType(task.getReviewType()).stream()
                .collect(java.util.stream.Collectors.toMap(CheckItemTemplateRecord::getItemKey, CheckItemTemplateRecord::getItemName, (left, right) -> left));
        return records.stream().map(record -> CheckItemView.from(record, categoryNames.get(record.getParentItemKey()), attachments(record.getId()))).toList();
    }

    @Transactional
    public CheckItemView submit(long taskId, long itemId, SubmitCheckItemCommand command, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (isFinished(task)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许修改检查项");
        }
        taskNodeAuthorizationService.requireCurrentTaskProcessor(taskId, currentUser);
        synchronizeIfActive(task);
        TaskCheckItemRecord record = taskCheckItemMapper.findByTaskIdAndId(taskId, itemId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务");
        }
        validateCommand(command, taskId, itemId);
        record.setCheckResult(command.result().name());
        record.setComment(command.comment());
        record.setRichText(command.richText());
        record.setLinkedOpinionId(command.linkedOpinionId());
        record.setStatus(CheckItemStatus.COMPLETED.name());
        if (taskCheckItemMapper.submit(record) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务");
        }
        appendAudit(record.getId(), "CHECK_ITEM_SUBMITTED", currentUser.id(), command.result().name());
        return CheckItemView.from(record, null, attachments(record.getId()));
    }

    /**
     * @author 王涛
     * @date 2026-09-18
     * @description 以互检单为原子单位提交多个检查项；不合格项自动建立或更新同源互检意见，图文提取意见以富文本字符串保存。
     */
    @Transactional
    public List<CheckItemView> submitBatch(long taskId, List<SubmitBatchItemCommand> commands, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (isFinished(task)) { throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许修改检查项"); }
        taskNodeAuthorizationService.requireCurrentTaskProcessor(taskId, currentUser);
        synchronizeIfActive(task);
        if (commands == null || commands.isEmpty()) { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "互检单至少需要提交一条检查项结果"); }
        Set<Long> duplicateGuard = new LinkedHashSet<>();
        List<CheckItemView> views = new java.util.ArrayList<>();
        for (SubmitBatchItemCommand command : commands) {
            if (!duplicateGuard.add(command.itemId())) { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "互检单中存在重复检查项：" + command.itemId()); }
            TaskCheckItemRecord record = taskCheckItemMapper.findByTaskIdAndId(taskId, command.itemId());
            if (record == null) { throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务"); }
            validateBatchCommand(command);
            validateAttachmentFiles(taskId, command.attachmentFileIds());
            Long opinionId = command.result() == CheckResult.FAIL ? createOrUpdateMutualOpinion(taskId, record, command, currentUser) : null;
            replaceAttachments(record.getId(), command.attachmentFileIds());
            record.setCheckResult(command.result().name()); record.setComment(command.comment()); record.setRichText(command.richText()); record.setLinkedOpinionId(opinionId);
            record.setStatus(CheckItemStatus.COMPLETED.name());
            if (taskCheckItemMapper.submit(record) != 1) { throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务"); }
            appendAudit(record.getId(), "CHECK_ITEM_BATCH_SUBMITTED", currentUser.id(), command.result().name());
            views.add(CheckItemView.from(record, null, attachments(record.getId())));
        }
        return List.copyOf(views);
    }

    @Transactional
    public void materializeActiveTaskItems(long taskId) {
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (!isFinished(task)) {
            synchronizeIfActive(task);
        }
    }

    private List<CheckItemTemplateRecord> synchronizeIfActive(ReviewTaskRecord task) {
        if (isFinished(task)) {
            return List.of();
        }
        List<CheckItemTemplateRecord> allTemplates = templateMapper.findEnabledByReviewType(task.getReviewType());
        Set<String> categoryKeys = allTemplates.stream().map(CheckItemTemplateRecord::getParentItemKey).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<CheckItemTemplateRecord> templates = allTemplates.stream().filter(template -> !categoryKeys.contains(template.getItemKey())).toList();
        Map<Long, TaskCheckItemRecord> existing = new HashMap<>();
        for (TaskCheckItemRecord item : taskCheckItemMapper.findByTaskId(task.getId())) {
            existing.put(item.getTemplateItemId(), item);
        }
        for (CheckItemTemplateRecord template : templates) {
            TaskCheckItemRecord snapshot = snapshotOf(task.getId(), template);
            if (existing.containsKey(template.getId())) {
                taskCheckItemMapper.refreshTemplateSnapshot(snapshot);
            } else {
                snapshot.setId(taskCheckItemMapper.nextId());
                snapshot.setStatus(CheckItemStatus.PENDING.name());
                snapshot.setVersion(0L);
                taskCheckItemMapper.insert(snapshot);
            }
        }
        return templates;
    }

    private TaskCheckItemRecord snapshotOf(long taskId, CheckItemTemplateRecord template) {
        TaskCheckItemRecord item = new TaskCheckItemRecord();
        item.setTaskId(taskId);
        item.setTemplateItemId(template.getId());
        item.setTemplateItemKey(template.getItemKey());
        item.setParentItemKey(template.getParentItemKey());
        item.setItemName(template.getItemName());
        item.setSortNo(template.getSortNo());
        return item;
    }

    private ReviewTaskRecord requireVisibleTask(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (permissionPolicy.has(currentUser.roles(), Permission.VIEW_MUTUAL_CHECK_OPINION)
                || assignmentAccessMapper.isAssignedToTask(taskId, currentUser.id())) {
            return task;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务的互检检查项");
    }

    private ReviewTaskRecord requireTaskExists(long taskId) {
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        }
        return task;
    }

    private boolean isFinished(ReviewTaskRecord task) {
        return TaskStatus.FINISHED.name().equals(task.getStatus());
    }

    private void appendAudit(long itemId, String action, long operatorId, String detail) {
        auditMapper.insert(new OperationAuditRecord("TASK_CHECK_ITEM", itemId, action, operatorId, detail));
    }

    private void validateCommand(SubmitCheckItemCommand command, long taskId, long itemId) {
        boolean hasComment = command.comment() != null && !command.comment().isBlank();
        if ((command.result() == CheckResult.FAIL || command.result() == CheckResult.NOT_APPLICABLE) && !hasComment) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不合格或不适用的检查项必须填写说明");
        }
        if (command.result() == CheckResult.FAIL && command.linkedOpinionId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不合格检查项必须关联评审意见");
        }
        if (command.result() == CheckResult.FAIL) {
            ReviewOpinionRecord opinion = opinionMapper.findById(command.linkedOpinionId());
            if (opinion == null || !Long.valueOf(taskId).equals(opinion.getTaskId())
                    || !"MUTUAL_CHECK_ITEM".equals(opinion.getSourceType()) || !Long.valueOf(itemId).equals(opinion.getSourceItemId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不合格检查项必须关联本任务当前检查项提出的意见");
            }
        }
    }

    private void validateBatchCommand(SubmitBatchItemCommand command) {
        if (command.result() == null) { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检查结果不能为空"); }
        if (command.result() == CheckResult.FAIL
                && (command.richText() == null || command.richText().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不合格检查项必须填写富文本提取意见");
        }
        if (command.result() == CheckResult.NOT_APPLICABLE && (command.comment() == null || command.comment().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不适用的检查项必须填写检查说明");
        }
    }

    private void validateAttachmentFiles(long taskId, List<Long> fileIds) {
        for (Long fileId : fileIds) {
            ReviewFileRecord file = fileMapper.findById(fileId);
            if (file == null || !Long.valueOf(taskId).equals(file.getTaskId())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项图片不存在或不属于当前任务");
            }
        }
    }

    private Long createOrUpdateMutualOpinion(long taskId, TaskCheckItemRecord item, SubmitBatchItemCommand command, CurrentUser currentUser) {
        ReviewOpinionRecord opinion = opinionMapper.findActiveMutualCheckItemOpinion(taskId, item.getId());
        if (opinion == null) {
            opinion = new ReviewOpinionRecord(); opinion.setId(opinionMapper.nextOpinionId()); opinion.setTaskId(taskId);
            opinion.setSourceType("MUTUAL_CHECK_ITEM"); opinion.setSourceItemId(item.getId()); opinion.setSeverity("GENERAL");
            opinion.setContent(command.richText().trim()); opinion.setRichText(command.richText().trim()); opinion.setRaisedBy(currentUser.id());
            opinion.setStatus("PENDING_REPLY"); opinionMapper.insert(opinion);
        } else {
            opinion.setContent(command.richText().trim()); opinion.setRichText(command.richText().trim());
            if (opinionMapper.updateContent(opinion) != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "关联互检意见已被其他操作更新，请刷新后重试"); }
        }
        return opinion.getId();
    }

    private void replaceAttachments(long itemId, List<Long> fileIds) {
        attachmentMapper.deleteByCheckItemId(itemId);
        for (int index = 0; index < fileIds.size(); index++) { attachmentMapper.insert(new CheckItemAttachmentRecord(itemId, fileIds.get(index), index)); }
    }

    private List<CheckItemAttachmentView> attachments(long itemId) {
        List<CheckItemAttachmentViewRecord> records = attachmentMapper.findByCheckItemId(itemId);
        return (records == null ? List.<CheckItemAttachmentViewRecord>of() : records).stream().map(CheckItemAttachmentView::from).toList();
    }

    public record SubmitCheckItemCommand(CheckResult result, String comment, String richText, Long linkedOpinionId) {
        public SubmitCheckItemCommand(CheckResult result, String comment, Long linkedOpinionId) {
            this(result, comment, null, linkedOpinionId);
        }
    }
    public record SubmitBatchItemCommand(long itemId, CheckResult result, String comment, String richText, List<Long> attachmentFileIds) {
        public SubmitBatchItemCommand(long itemId, CheckResult result, String comment, List<Long> attachmentFileIds) {
            this(itemId, result, comment, null, attachmentFileIds);
        }
        public SubmitBatchItemCommand { attachmentFileIds = attachmentFileIds == null ? List.of() : List.copyOf(attachmentFileIds); }
    }

    public record CheckItemView(Long id, String templateItemKey, String parentItemKey, String itemName, int sortNo,
                                String categoryName, CheckResult result, String comment, String richText, Long linkedOpinionId, List<CheckItemAttachmentView> attachments,
                                CheckItemStatus status) {
        static CheckItemView from(TaskCheckItemRecord record, String categoryName, List<CheckItemAttachmentView> attachments) {
            return new CheckItemView(record.getId(), record.getTemplateItemKey(), record.getParentItemKey(), record.getItemName(),
                    record.getSortNo(), categoryName, record.getCheckResult() == null ? null : CheckResult.valueOf(record.getCheckResult()), record.getComment(), record.getRichText(),
                    record.getLinkedOpinionId(), attachments, CheckItemStatus.valueOf(record.getStatus()));
        }
    }
    public record CheckItemAttachmentView(Long fileId, int sortNo, String fileName, String fileCategory, String previewUrl) {
        static CheckItemAttachmentView from(CheckItemAttachmentViewRecord record) { return new CheckItemAttachmentView(record.getFileId(), record.getSortNo(), record.getFileName(), record.getFileCategory(), record.getPreviewUrl()); }
    }
}
