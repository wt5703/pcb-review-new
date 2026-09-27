package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.application.CurrentUser;
import com.bms.identity.application.TaskNodeAuthorizationService;
import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.infrastructure.TaskAssignmentAccessMapper;
import com.bms.review.domain.CheckItemStatus;
import com.bms.review.domain.CheckResult;
import com.bms.review.domain.OpinionStatus;
import com.bms.review.infrastructure.CheckItemTemplateMapper;
import com.bms.review.infrastructure.CheckItemTemplateRecord;
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
 * @description 维护任务内固定互检检查项：实例仅关联模板 itemId，名称从模板读取；进行中任务同步当前模板，已结束任务仅读取既有检查结果。
 */
@Service
public class TaskCheckItemApplicationService {
    private final ReviewTaskMapper taskMapper;
    private final CheckItemTemplateMapper templateMapper;
    private final TaskCheckItemMapper taskCheckItemMapper;
    private final TaskAssignmentAccessMapper assignmentAccessMapper;
    private final TaskNodeAuthorizationService taskNodeAuthorizationService;
    private final ReviewOpinionMapper opinionMapper;
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public TaskCheckItemApplicationService(ReviewTaskMapper taskMapper, CheckItemTemplateMapper templateMapper,
                                           TaskCheckItemMapper taskCheckItemMapper, TaskAssignmentAccessMapper assignmentAccessMapper,
                                           TaskNodeAuthorizationService taskNodeAuthorizationService, ReviewOpinionMapper opinionMapper) {
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.taskNodeAuthorizationService = taskNodeAuthorizationService;
        this.opinionMapper = opinionMapper;
    }

    @Transactional
    public List<CheckItemCategoryView> list(long taskId, CurrentUser currentUser) {
        ReviewTaskRecord task = requireVisibleTask(taskId, currentUser);
        List<CheckItemTemplateRecord> templates = synchronizeIfActive(task);
        List<TaskCheckItemRecord> records = taskCheckItemMapper.findByTaskId(taskId);
        if (!isFinished(task)) {
            Set<Long> activeTemplateIds = templates.stream().map(CheckItemTemplateRecord::getId).collect(java.util.stream.Collectors.toSet());
            records = records.stream().filter(record -> activeTemplateIds.contains(record.getItemId())).toList();
        }
        Map<Long, CheckItemTemplateRecord> templatesByItemId = templatesByItemId(records, templates, isFinished(task));
        Map<Long, List<TaskCheckItemRecord>> itemsByParentId = records.stream()
                .filter(record -> record.getParentId() != null)
                .collect(java.util.stream.Collectors.groupingBy(TaskCheckItemRecord::getParentId));
        return records.stream()
                .filter(record -> record.getParentId() == null)
                .sorted(java.util.Comparator.comparing(TaskCheckItemRecord::getSortNo))
                .map(category -> new CheckItemCategoryView(new CheckItemCategoryInfo(category.getId(), task.getReviewType(),
                        itemNameOf(category, templatesByItemId), category.getSortNo()),
                        itemsByParentId.getOrDefault(category.getId(), List.of()).stream()
                                .sorted(java.util.Comparator.comparing(TaskCheckItemRecord::getSortNo))
                                .map(item -> CheckItemListItemView.from(item, itemNameOf(item, templatesByItemId)))
                                .toList()))
                .toList();
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
        requireLeafCheckItem(record);
        validateCommand(command);
        ReviewOpinionRecord opinion = command.result() == CheckResult.FAIL
                ? createOrUpdateMutualOpinion(taskId, record, command.comment(), command.richText(), currentUser) : null;
        if (command.result() == CheckResult.PASS) {
            withdrawUnresolvedMutualOpinion(taskId, record);
        }
        record.setCheckResult(command.result().name());
        record.setComment(command.comment());
        record.setRichText(command.richText());
        record.setStatus(CheckItemStatus.COMPLETED.name());
        if (taskCheckItemMapper.submit(record) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务");
        }
        return CheckItemView.from(record, opinion, itemNameOf(record));
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
            requireLeafCheckItem(record);
            validateBatchCommand(command);
            ReviewOpinionRecord opinion = command.result() == CheckResult.FAIL ? createOrUpdateMutualOpinion(taskId, record, command.comment(), command.richText(), currentUser) : null;
            if (command.result() == CheckResult.PASS) {
                withdrawUnresolvedMutualOpinion(taskId, record);
            }
            record.setCheckResult(command.result().name()); record.setComment(command.comment()); record.setRichText(command.richText());
            record.setStatus(CheckItemStatus.COMPLETED.name());
            if (taskCheckItemMapper.submit(record) != 1) { throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务"); }
            views.add(CheckItemView.from(record, opinion, itemNameOf(record)));
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
        List<CheckItemTemplateRecord> templates = allTemplates;
        Map<Long, TaskCheckItemRecord> existing = new HashMap<>();
        for (TaskCheckItemRecord item : taskCheckItemMapper.findByTaskId(task.getId())) {
            existing.put(item.getItemId(), item);
        }
        Map<Long, CheckItemTemplateRecord> templateById = allTemplates.stream()
                .collect(java.util.stream.Collectors.toMap(CheckItemTemplateRecord::getId, template -> template, (left, right) -> left));
        for (CheckItemTemplateRecord template : templates.stream().filter(item -> item.getParentId() == null).toList()) {
            materializeSnapshot(task.getId(), template, null, existing);
        }
        for (CheckItemTemplateRecord template : templates.stream().filter(item -> item.getParentId() != null).toList()) {
            CheckItemTemplateRecord parentTemplate = templateById.get(template.getParentId());
            TaskCheckItemRecord parent = parentTemplate == null ? null : existing.get(parentTemplate.getId());
            materializeSnapshot(task.getId(), template, parent == null ? null : parent.getId(), existing);
        }
        return templates;
    }

    private void materializeSnapshot(long taskId, CheckItemTemplateRecord template, Long parentId, Map<Long, TaskCheckItemRecord> existing) {
        TaskCheckItemRecord snapshot = snapshotOf(taskId, template, parentId);
        TaskCheckItemRecord current = existing.get(template.getId());
        if (current != null) {
            snapshot.setId(current.getId());
            snapshot.setStatus(current.getStatus());
            taskCheckItemMapper.refreshTemplateSnapshot(snapshot);
        } else {
            snapshot.setId(taskCheckItemMapper.nextId());
            snapshot.setStatus(CheckItemStatus.PENDING.name());
            taskCheckItemMapper.insert(snapshot);
        }
        existing.put(template.getId(), snapshot);
    }

    private TaskCheckItemRecord snapshotOf(long taskId, CheckItemTemplateRecord template, Long parentId) {
        TaskCheckItemRecord item = new TaskCheckItemRecord();
        item.setTaskId(taskId);
        item.setItemId(template.getId());
        item.setParentId(parentId);
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

    private void validateCommand(SubmitCheckItemCommand command) {
        validateResult(command.result(), command.comment());
    }

    private void requireLeafCheckItem(TaskCheckItemRecord record) {
        if (record.getParentId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "互检类别不能直接提交，必须提交其下检查项");
        }
    }

    private void validateBatchCommand(SubmitBatchItemCommand command) {
        validateResult(command.result(), command.comment());
    }

    private void validateResult(CheckResult result, String comment) {
        if (result == null) { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检查结果不能为空"); }
        if ((result == CheckResult.FAIL || result == CheckResult.NC) && (comment == null || comment.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不合格或 NC 检查项必须填写意见");
        }
    }

    private ReviewOpinionRecord createOrUpdateMutualOpinion(long taskId, TaskCheckItemRecord item, String comment, String richText, CurrentUser currentUser) {
        String opinionContent = richText == null || richText.isBlank() ? comment.trim() : richText.trim();
        ReviewOpinionRecord opinion = opinionMapper.findActiveMutualCheckItemOpinion(taskId, item.getId());
        if (opinion == null) {
            opinion = new ReviewOpinionRecord(); opinion.setId(opinionMapper.nextOpinionId()); opinion.setTaskId(taskId);
            opinion.setSourceType("MUTUAL_CHECK_ITEM"); opinion.setSourceItemId(item.getId()); opinion.setSeverity("GENERAL");
            opinion.setContent(opinionContent); opinion.setRichText(opinionContent); opinion.setRaisedBy(currentUser.id()); opinion.setRaisedByName(currentUser.resolvedDisplayName());
            opinion.setStatus("PENDING_REPLY"); opinionMapper.insert(opinion);
        } else {
            opinion.setContent(opinionContent); opinion.setRichText(opinionContent);
            if (opinionMapper.updateContent(opinion) != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "关联互检意见已被其他操作更新，请刷新后重试"); }
        }
        return opinion;
    }

    /**
     * 检查项由不合格改回合格时，原问题已不再需要设计者答复或专家确认。
     * 仅撤回尚未确认通过的同源意见，保留已经闭环的历史记录。
     */
    private void withdrawUnresolvedMutualOpinion(long taskId, TaskCheckItemRecord item) {
        ReviewOpinionRecord opinion = opinionMapper.findActiveMutualCheckItemOpinion(taskId, item.getId());
        if (opinion == null || OpinionStatus.CONFIRMED_PASS.name().equals(opinion.getStatus())) {
            return;
        }
        opinion.setStatus(OpinionStatus.WITHDRAWN.name());
        if (opinionMapper.updateStatus(opinion) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "关联互检意见状态更新失败，请刷新后重试");
        }
    }

    private Map<Long, CheckItemTemplateRecord> templatesByItemId(List<TaskCheckItemRecord> records,
                                                                    List<CheckItemTemplateRecord> activeTemplates,
                                                                    boolean finished) {
        if (records.isEmpty()) {
            return Map.of();
        }
        List<CheckItemTemplateRecord> source = finished
                ? templateMapper.findByIds(records.stream().map(TaskCheckItemRecord::getItemId).distinct().toList())
                : activeTemplates;
        return source.stream().collect(java.util.stream.Collectors.toMap(CheckItemTemplateRecord::getId,
                template -> template, (left, right) -> left));
    }

    private String itemNameOf(TaskCheckItemRecord record) {
        CheckItemTemplateRecord template = templateMapper.findById(record.getItemId());
        if (template == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "互检检查项模板不存在");
        }
        return template.getItemName();
    }

    private String itemNameOf(TaskCheckItemRecord record, Map<Long, CheckItemTemplateRecord> templatesByItemId) {
        CheckItemTemplateRecord template = templatesByItemId.get(record.getItemId());
        if (template == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "互检检查项模板不存在");
        }
        return template.getItemName();
    }

    public record SubmitCheckItemCommand(CheckResult result, String comment, String richText) { }
    public record SubmitBatchItemCommand(long itemId, CheckResult result, String comment, String richText) { }

    public record CheckItemCategoryView(CheckItemCategoryInfo category, List<CheckItemListItemView> items) { }
    public record CheckItemCategoryInfo(Long id, String reviewType, String itemName, int sortNo) { }
    /**
     * 互检单查询子项：检查结论、文字意见和富文本只存在于可空 opinion 内，避免把同一信息平铺在 items 中。
     */
    public record CheckItemListItemView(Long id, String itemName, int sortNo, CheckItemListOpinionView opinion) {
        static CheckItemListItemView from(TaskCheckItemRecord record, String itemName) {
            CheckItemListOpinionView opinion = record.getCheckResult() == null ? null
                    : new CheckItemListOpinionView(CheckResult.valueOf(record.getCheckResult()), record.getComment(), record.getRichText());
            return new CheckItemListItemView(record.getId(), itemName, record.getSortNo(), opinion);
        }
    }
    public record CheckItemListOpinionView(CheckResult result, String comment, String richText) { }
    public record CheckItemView(Long id, String itemName, int sortNo,
                                CheckResult result, String comment, String richText, CheckItemOpinionView opinion,
                                CheckItemStatus status) {
        static CheckItemView from(TaskCheckItemRecord record, ReviewOpinionRecord opinion, String itemName) {
            return new CheckItemView(record.getId(), itemName,
                    record.getSortNo(), record.getCheckResult() == null ? null : CheckResult.valueOf(record.getCheckResult()), record.getComment(), record.getRichText(),
                    opinion == null ? null : CheckItemOpinionView.from(opinion), CheckItemStatus.valueOf(record.getStatus()));
        }
    }
    public record CheckItemOpinionView(Long id, String content, String richText, Long raisedBy, String raisedByName, String severity,
                                       OpinionStatus status, java.time.LocalDateTime createdAt) {
        static CheckItemOpinionView from(ReviewOpinionRecord opinion) {
            return new CheckItemOpinionView(opinion.getId(), opinion.getContent(), opinion.getRichText(), opinion.getRaisedBy(),
                    opinion.getRaisedByName(), opinion.getSeverity(), OpinionStatus.valueOf(opinion.getStatus()), opinion.getCreatedAt());
        }
    }
}
