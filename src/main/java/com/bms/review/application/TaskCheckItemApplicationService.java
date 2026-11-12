package com.bms.review.application;

import com.bms.common.BusinessException;
import com.bms.common.ErrorCode;
import com.bms.identity.infrastructure.UserCenterUserProfileClient;
import com.bms.task.application.TaskProcessorAuthorizationService;
import com.bms.review.domain.CheckItemStatus;
import com.bms.review.domain.CheckResult;
import com.bms.review.domain.OpinionStatus;
import com.bms.review.domain.OpinionSeverity;
import com.bms.review.domain.OpinionSourceType;
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
    private final TaskProcessorAuthorizationService taskProcessorAuthorizationService;
    private final ReviewOpinionMapper opinionMapper;
    private final UserCenterUserProfileClient userProfileClient;

    public TaskCheckItemApplicationService(ReviewTaskMapper taskMapper, CheckItemTemplateMapper templateMapper,
                                           TaskCheckItemMapper taskCheckItemMapper, TaskProcessorAuthorizationService taskProcessorAuthorizationService,
                                           ReviewOpinionMapper opinionMapper, UserCenterUserProfileClient userProfileClient) {
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.taskProcessorAuthorizationService = taskProcessorAuthorizationService;
        this.opinionMapper = opinionMapper;
        this.userProfileClient = userProfileClient;
    }

    @Transactional
    public List<CheckItemCategoryView> list(long taskId) {
        taskMapper.lockById(taskId);
        ReviewTaskRecord task = requireTaskExists(taskId);
        List<TaskCheckItemRecord> records = taskCheckItemMapper.findByTaskId(taskId);
        List<CheckItemTemplateRecord> templates;
        if (records.isEmpty() && !isFinished(task)) {
            templates = materializeTemplate(task);
            records = taskCheckItemMapper.findByTaskId(taskId);
        } else {
            templates = records.isEmpty() ? List.of()
                    : templateMapper.findByIds(records.stream().map(TaskCheckItemRecord::getItemId).distinct().toList());
        }
        Map<Long, CheckItemTemplateRecord> templatesByItemId = templates.stream()
                .collect(java.util.stream.Collectors.toMap(CheckItemTemplateRecord::getId, template -> template));
        Map<Long, List<TaskCheckItemRecord>> itemsByParentId = records.stream().filter(record -> record.getParentId() != null)
                .collect(java.util.stream.Collectors.groupingBy(TaskCheckItemRecord::getParentId));
        return records.stream().filter(record -> record.getParentId() == null)
                .sorted(java.util.Comparator.comparing(TaskCheckItemRecord::getSortNo).thenComparing(TaskCheckItemRecord::getId))
                .map(category -> new CheckItemCategoryView(new CheckItemCategoryInfo(category.getId(),
                        itemNameOf(category, templatesByItemId), category.getSortNo()),
                        childItems(category.getId(), itemsByParentId, templatesByItemId)))
                .toList();
    }

    private List<CheckItemListItemView> childItems(long parentId, Map<Long, List<TaskCheckItemRecord>> itemsByParentId,
                                                   Map<Long, CheckItemTemplateRecord> templatesByItemId) {
        return itemsByParentId.getOrDefault(parentId, List.of()).stream()
                .sorted(java.util.Comparator.comparing(TaskCheckItemRecord::getSortNo).thenComparing(TaskCheckItemRecord::getId))
                .map(item -> CheckItemListItemView.from(item, templateOf(item, templatesByItemId),
                        childItems(item.getId(), itemsByParentId, templatesByItemId)))
                .toList();
    }

    @Transactional
    public CheckItemView submit(long taskId, long itemId, SubmitCheckItemCommand command, String operatorEmployeeNo, String operatorName) {
        // 1. 只校验任务状态与当前节点处理权限；提交时绝不重新同步互检单模板。
        taskMapper.lockById(taskId);
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (isFinished(task)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许修改检查项");
        }
        taskProcessorAuthorizationService.requireCurrentTaskProcessor(taskId, operatorEmployeeNo);

        // 2. 仅定位任务已经生成的检查项快照，防止模板修改影响本次保存。
        TaskCheckItemRecord record = taskCheckItemMapper.findByTaskIdAndId(taskId, itemId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务");
        }
        requireLeafCheckItem(record);
        validateCommand(command);

        // 3. 不合格创建/更新同源意见；专家改判为合格即确认该意见通过。
        ReviewOpinionRecord opinion = command.result() == CheckResult.FAIL
                ? createOrUpdateMutualOpinion(taskId, record, command.comment(), command.richText(), operatorEmployeeNo, operatorName, false) : null;
        if (command.result() == CheckResult.PASS) {
            confirmMutualOpinionWhenCheckPass(taskId, record, false);
        }

        // 4. 将结论、文字和富文本原样写入任务快照，再返回最新结果。
        record.setCheckResult(command.result().name());
        record.setComment(command.comment());
        record.setRichText(command.richText());
        record.setStatus(CheckItemStatus.COMPLETED.name());
        if (taskCheckItemMapper.submit(record) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务");
        }
        return toCheckItemView(record, opinion, itemNameOf(record));
    }

    /**
     * @author 王涛
     * @date 2026-09-18
     * @description 以互检单为原子单位提交多个检查项；不合格项自动建立或更新同源互检意见，NC 可不填写文字或富文本说明。
     */
    @Transactional
    public List<CheckItemView> submitBatch(long taskId, List<SubmitBatchItemCommand> commands, String operatorEmployeeNo, String operatorName) {
        return writeBatch(taskId, commands, operatorEmployeeNo, operatorName, false);
    }

    /**
     * 保存互检单草稿。草稿不校验意见文字和富文本是否为空；FAIL 产生的意见带 isDraft 标记，
     * 不进入设计者答复、意见统计和设计者待办。草稿仍会阻止专家确认提交及设计者推进流程，
     * 后续调用 submitBatch 后才会发布为正式意见。
     */
    @Transactional
    public List<CheckItemView> saveBatch(long taskId, List<SubmitBatchItemCommand> commands, String operatorEmployeeNo, String operatorName) {
        return writeBatch(taskId, commands, operatorEmployeeNo, operatorName, true);
    }

    private List<CheckItemView> writeBatch(long taskId, List<SubmitBatchItemCommand> commands,
                                           String operatorEmployeeNo, String operatorName, boolean draft) {
        // 1. 批量提交同样只处理既有任务快照，不在保存过程中读取或同步模板。
        taskMapper.lockById(taskId);
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (isFinished(task)) { throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "当前任务状态不允许修改检查项"); }
        taskProcessorAuthorizationService.requireCurrentTaskProcessor(taskId, operatorEmployeeNo);
        if (commands == null || commands.isEmpty()) { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "互检单至少需要提交一条检查项结果"); }

        // 2. 先逐项校验归属和重复提交；任一项失败会由事务整体回滚。
        Set<Long> duplicateGuard = new LinkedHashSet<>();
        List<CheckItemView> views = new java.util.ArrayList<>();
        for (SubmitBatchItemCommand command : commands) {
            if (!duplicateGuard.add(command.itemId())) { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "互检单中存在重复检查项：" + command.itemId()); }
            TaskCheckItemRecord record = taskCheckItemMapper.findByTaskIdAndId(taskId, command.itemId());
            if (record == null) { throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务"); }
            requireLeafCheckItem(record);
            if (!draft) {
                validateResult(command.result(), command.comment());
            }

            // 3. 结论与同源意见保持一致：保存时 FAIL 仅写草稿，提交时才发布为设计者可答复的意见。
            ReviewOpinionRecord opinion = command.result() == CheckResult.FAIL
                    ? createOrUpdateMutualOpinion(taskId, record, command.comment(), command.richText(), operatorEmployeeNo, operatorName, draft) : null;
            if (command.result() == CheckResult.PASS) {
                confirmMutualOpinionWhenCheckPass(taskId, record, draft);
            }

            // 4. 持久化当前任务检查项的结论和说明，不影响同任务的其他子项。
            record.setCheckResult(command.result() == null ? null : command.result().name()); record.setComment(command.comment()); record.setRichText(command.richText());
            record.setStatus(draft ? CheckItemStatus.SAVED.name() : CheckItemStatus.COMPLETED.name());
            if (taskCheckItemMapper.submit(record) != 1) { throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务"); }
            views.add(toCheckItemView(record, opinion, itemNameOf(record)));
        }
        return List.copyOf(views);
    }

    @Transactional
    public void materializeActiveTaskItems(long taskId) {
        taskMapper.lockById(taskId);
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (!isFinished(task) && taskCheckItemMapper.findByTaskId(taskId).isEmpty()) {
            materializeTemplate(task);
        }
    }

    /** 已有任务快照继续引用原模板记录，重新导入只影响之后生成的快照。 */
    private List<CheckItemTemplateRecord> materializeTemplate(ReviewTaskRecord task) {
        List<CheckItemTemplateRecord> templates = templateMapper.findEnabledByReviewType(task.getReviewType());
        Map<Long, List<CheckItemTemplateRecord>> childrenByParentId = templates.stream().filter(item -> item.getParentId() != null)
                .collect(java.util.stream.Collectors.groupingBy(CheckItemTemplateRecord::getParentId));
        templates.stream().filter(item -> item.getParentId() == null)
                .forEach(root -> materializeSnapshot(task.getId(), root, null, childrenByParentId));
        return templates;
    }

    private void materializeSnapshot(long taskId, CheckItemTemplateRecord template, Long parentId,
                                     Map<Long, List<CheckItemTemplateRecord>> childrenByParentId) {
        TaskCheckItemRecord item = new TaskCheckItemRecord();
        item.setId(taskCheckItemMapper.nextId());
        item.setTaskId(taskId);
        item.setItemId(template.getId());
        item.setParentId(parentId);
        item.setSortNo(template.getSortNo());
        item.setStatus(CheckItemStatus.PENDING.name());
        taskCheckItemMapper.insert(item);
        childrenByParentId.getOrDefault(template.getId(), List.of())
                .forEach(child -> materializeSnapshot(taskId, child, item.getId(), childrenByParentId));
    }

    private ReviewTaskRecord requireTaskExists(long taskId) {
        ReviewTaskRecord task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评审任务不存在");
        }
        return task;
    }

    private boolean isFinished(ReviewTaskRecord task) {
        return TaskStatus.isClosed(task.getStatus());
    }

    private void validateCommand(SubmitCheckItemCommand command) {
        validateResult(command.result(), command.comment());
    }

    private CheckItemView toCheckItemView(TaskCheckItemRecord record, ReviewOpinionRecord opinion, String itemName) {
        String raisedByName = opinion == null ? null
                : userProfileClient.getByEmployeeNo(opinion.getRaisedByEmployeeNo()).displayName();
        return CheckItemView.from(record, opinion, itemName, raisedByName);
    }

    private void requireLeafCheckItem(TaskCheckItemRecord record) {
        if (record.getParentId() == null || taskCheckItemMapper.hasChildren(record.getTaskId(), record.getId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "互检类别不能直接提交，必须提交其下检查项");
        }
    }

    private void validateResult(CheckResult result, String comment) {
        if (result == null) { throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检查结果不能为空"); }
        if (result == CheckResult.FAIL && (comment == null || comment.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不合格检查项必须填写意见");
        }
    }

    private ReviewOpinionRecord createOrUpdateMutualOpinion(long taskId, TaskCheckItemRecord item, String comment, String richText,
                                                            String operatorEmployeeNo, String operatorName, boolean draft) {
        // comment 是问题描述，必须保留前端传入的纯文本；richText 仅保存富文本内容。
        // 未传富文本时才以 comment 作为富文本回显的兜底值，不能反向覆盖 comment。
        String opinionComment = normalizeText(comment);
        String opinionRichText = normalizeText(richText);
        if (!draft && opinionRichText.isBlank()) {
            opinionRichText = opinionComment;
        }
        ReviewOpinionRecord opinion = opinionMapper.findActiveMutualCheckItemOpinion(taskId, item.getId(), OpinionSourceType.MUTUAL_CHECK_ITEM);
        if (opinion == null) {
            opinion = new ReviewOpinionRecord(); opinion.setId(opinionMapper.nextOpinionId()); opinion.setTaskId(taskId);
            opinion.setSourceType(OpinionSourceType.MUTUAL_CHECK_ITEM); opinion.setSourceItemId(item.getId()); opinion.setSeverity(OpinionSeverity.GENERAL);
            opinion.setComment(opinionComment); opinion.setRichText(opinionRichText); opinion.setRaisedByEmployeeNo(operatorEmployeeNo); opinion.setRaisedByName(operatorName);
            opinion.setDraft(draft);
            opinion.setStatus(OpinionStatus.PENDING_REPLY.name()); opinionMapper.insert(opinion);
        } else {
            if (draft && !opinion.isDraft() && !isClosedOpinion(opinion)) {
                throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "已提交的互检意见不能保存为草稿");
            }
            String originalStatus = opinion.getStatus();
            opinion.setComment(opinionComment); opinion.setRichText(opinionRichText); opinion.setDraft(draft);
            if (opinionMapper.updateOpinion(opinion) != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "关联互检意见已被其他操作更新，请刷新后重试"); }
            // 已确认通过的检查项再次被判定为不合格时，重新进入待设计者答复状态。
            if (!OpinionStatus.PENDING_REPLY.name().equals(originalStatus)) {
                opinion.setStatus(OpinionStatus.PENDING_REPLY.name());
                if (opinionMapper.updateStatus(opinion) != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "关联互检意见状态更新失败，请刷新后重试"); }
            }
        }
        return opinion;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 专家将检查项由不合格改回合格，等同于确认该项意见已经通过。
     * 此处直接更新关联互检意见，意见总览会将其纳入 confirmedPass 统计。
     */
    private void confirmMutualOpinionWhenCheckPass(long taskId, TaskCheckItemRecord item, boolean draft) {
        ReviewOpinionRecord opinion = opinionMapper.findActiveMutualCheckItemOpinion(taskId, item.getId(), OpinionSourceType.MUTUAL_CHECK_ITEM);
        if (opinion == null) {
            return;
        }
        if (draft && !opinion.isDraft() && !isClosedOpinion(opinion)) {
            throw new BusinessException(ErrorCode.OPINION_STATUS_CONFLICT, "已提交的互检意见不能保存为草稿");
        }
        if (opinion.isDraft() != draft) {
            opinion.setDraft(draft);
            if (opinionMapper.updateOpinion(opinion) != 1) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, "关联互检意见草稿状态更新失败，请刷新后重试");
            }
        }
        if (OpinionStatus.CONFIRMED_PASS.name().equals(opinion.getStatus())) {
            return;
        }
        opinion.setStatus(OpinionStatus.CONFIRMED_PASS.name());
        if (opinionMapper.updateStatus(opinion) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "关联互检意见状态更新失败，请刷新后重试");
        }
    }

    private boolean isClosedOpinion(ReviewOpinionRecord opinion) {
        return OpinionStatus.CONFIRMED_PASS.name().equals(opinion.getStatus())
                || OpinionStatus.WITHDRAWN.name().equals(opinion.getStatus());
    }

    private String itemNameOf(TaskCheckItemRecord record) {
        CheckItemTemplateRecord template = templateMapper.findById(record.getItemId());
        if (template == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "互检检查项模板不存在");
        }
        return template.getItemName();
    }

    private String itemNameOf(TaskCheckItemRecord record, Map<Long, CheckItemTemplateRecord> templatesByItemId) {
        return templateOf(record, templatesByItemId).getItemName();
    }

    private CheckItemTemplateRecord templateOf(TaskCheckItemRecord record, Map<Long, CheckItemTemplateRecord> templatesByItemId) {
        CheckItemTemplateRecord template = templatesByItemId.get(record.getItemId());
        if (template == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "互检检查项模板不存在");
        }
        return template;
    }

    public record SubmitCheckItemCommand(CheckResult result, String comment, String richText) { }
    public record SubmitBatchItemCommand(long itemId, CheckResult result, String comment, String richText) { }

    public record CheckItemCategoryView(CheckItemCategoryInfo category, List<CheckItemListItemView> items) { }
    public record CheckItemCategoryInfo(Long id, String itemName, int sortNo) { }
    /**
     * 互检单查询子项：检查结论、文字意见和富文本只存在于可空 opinion 内，避免把同一信息平铺在 items 中。
     * 模板富文本不属于任务检查项列表响应，页面只使用检查项名称。
     */
    public record CheckItemListItemView(Long id, String itemName, int sortNo, CheckItemListOpinionView opinion,
                                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                                        List<CheckItemListItemView> items) {
        static CheckItemListItemView from(TaskCheckItemRecord record, CheckItemTemplateRecord template, List<CheckItemListItemView> items) {
            CheckItemListOpinionView opinion = record.getCheckResult() == null ? null
                    : new CheckItemListOpinionView(CheckResult.valueOf(record.getCheckResult()), record.getComment(), record.getRichText());
            return new CheckItemListItemView(record.getId(), template.getItemName(), record.getSortNo(), opinion, items);
        }
    }
    public record CheckItemListOpinionView(CheckResult result, String comment, String richText) { }
    public record CheckItemView(Long id, String itemName, int sortNo,
                                CheckResult result, String comment, String richText, CheckItemOpinionView opinion,
                                CheckItemStatus status) {
        static CheckItemView from(TaskCheckItemRecord record, ReviewOpinionRecord opinion, String itemName, String raisedByName) {
            return new CheckItemView(record.getId(), itemName,
                    record.getSortNo(), record.getCheckResult() == null ? null : CheckResult.valueOf(record.getCheckResult()), record.getComment(), record.getRichText(),
                    opinion == null ? null : CheckItemOpinionView.from(opinion, raisedByName), CheckItemStatus.valueOf(record.getStatus()));
        }
    }
    public record CheckItemOpinionView(Long id, String comment, String richText, String raisedByEmployeeNo, String raisedByName, OpinionSeverity severity,
                                       OpinionStatus status, java.time.LocalDateTime createdAt) {
        static CheckItemOpinionView from(ReviewOpinionRecord opinion, String raisedByName) {
            return new CheckItemOpinionView(opinion.getId(), opinion.getComment(), opinion.getRichText(), opinion.getRaisedByEmployeeNo(),
                    raisedByName, opinion.getSeverity(), OpinionStatus.valueOf(opinion.getStatus()), opinion.getCreatedAt());
        }
    }
}
