package com.leapmotor.pcbreview.review.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileMapper;
import com.leapmotor.pcbreview.file.infrastructure.ReviewFileRecord;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.application.TaskNodeAuthorizationService;
import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.identity.infrastructure.TaskAssignmentAccessMapper;
import com.leapmotor.pcbreview.review.domain.CheckItemStatus;
import com.leapmotor.pcbreview.review.domain.CheckResult;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemTemplateMapper;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemTemplateRecord;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemAttachmentMapper;
import com.leapmotor.pcbreview.review.infrastructure.CheckItemAttachmentRecord;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionMapper;
import com.leapmotor.pcbreview.review.infrastructure.ReviewOpinionRecord;
import com.leapmotor.pcbreview.review.infrastructure.TaskCheckItemMapper;
import com.leapmotor.pcbreview.review.infrastructure.TaskCheckItemRecord;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    public TaskCheckItemApplicationService(ReviewTaskMapper taskMapper, CheckItemTemplateMapper templateMapper,
                                           TaskCheckItemMapper taskCheckItemMapper, TaskAssignmentAccessMapper assignmentAccessMapper,
                                           TaskNodeAuthorizationService taskNodeAuthorizationService, ReviewOpinionMapper opinionMapper,
                                           ReviewFileMapper fileMapper, CheckItemAttachmentMapper attachmentMapper) {
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
        this.taskCheckItemMapper = taskCheckItemMapper;
        this.assignmentAccessMapper = assignmentAccessMapper;
        this.taskNodeAuthorizationService = taskNodeAuthorizationService;
        this.opinionMapper = opinionMapper;
        this.fileMapper = fileMapper;
        this.attachmentMapper = attachmentMapper;
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
        return records.stream().map(CheckItemView::from).toList();
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
        record.setLinkedOpinionId(command.linkedOpinionId());
        record.setStatus(CheckItemStatus.COMPLETED.name());
        record.setVersion(command.version());
        if (taskCheckItemMapper.submit(record) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "检查项已被其他操作更新，请刷新后重试");
        }
        record.setVersion(record.getVersion() + 1);
        return CheckItemView.from(record);
    }

    @Transactional
    public void materializeActiveTaskItems(long taskId) {
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (!isFinished(task)) {
            synchronizeIfActive(task);
        }
    }

    @Transactional
    public void attachFile(long taskId, long itemId, long fileId, int sortNo, CurrentUser currentUser) {
        ReviewTaskRecord task = requireTaskExists(taskId);
        if (isFinished(task)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_CONFLICT, "已结束任务不允许新增检查项附件");
        }
        taskNodeAuthorizationService.requireCurrentTaskProcessor(taskId, currentUser);
        if (taskCheckItemMapper.findByTaskIdAndId(taskId, itemId) == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "检查项不存在或不属于当前任务");
        }
        ReviewFileRecord file = fileMapper.findById(fileId);
        if (file == null || !Long.valueOf(taskId).equals(file.getTaskId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "附件文件不存在或不属于当前任务");
        }
        attachmentMapper.insert(new CheckItemAttachmentRecord(itemId, fileId, sortNo));
    }

    private List<CheckItemTemplateRecord> synchronizeIfActive(ReviewTaskRecord task) {
        if (isFinished(task)) {
            return List.of();
        }
        List<CheckItemTemplateRecord> templates = templateMapper.findEnabledByReviewType(task.getReviewType());
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

    public record SubmitCheckItemCommand(CheckResult result, String comment, Long linkedOpinionId, long version) {
    }

    public record CheckItemView(Long id, String templateItemKey, String parentItemKey, String itemName, int sortNo,
                                CheckResult result, String comment, Long linkedOpinionId, CheckItemStatus status, long version) {
        static CheckItemView from(TaskCheckItemRecord record) {
            return new CheckItemView(record.getId(), record.getTemplateItemKey(), record.getParentItemKey(), record.getItemName(),
                    record.getSortNo(), record.getCheckResult() == null ? null : CheckResult.valueOf(record.getCheckResult()), record.getComment(),
                    record.getLinkedOpinionId(), CheckItemStatus.valueOf(record.getStatus()), record.getVersion());
        }
    }
}
