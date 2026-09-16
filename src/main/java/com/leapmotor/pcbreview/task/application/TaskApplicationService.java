package com.leapmotor.pcbreview.task.application;

import com.leapmotor.pcbreview.common.BusinessException;
import com.leapmotor.pcbreview.common.ErrorCode;
import com.leapmotor.pcbreview.identity.application.CurrentUser;
import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.task.domain.ReviewTask;
import com.leapmotor.pcbreview.task.domain.ReviewType;
import com.leapmotor.pcbreview.task.domain.TaskStatus;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskMapper;
import com.leapmotor.pcbreview.task.infrastructure.ReviewTaskRecord;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * @author 王涛
 * @date 2026-09-14
 * @description 编排评审任务创建、提交、查询和可见性校验，协调当前用户上下文、任务领域模型与持久化接口，不承载具体 HTTP 协议细节。
 */


@Service
public class TaskApplicationService {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, ReviewTask> tasks = new ConcurrentHashMap<>();
    private final PermissionPolicy permissionPolicy = new PermissionPolicy();
    private final ReviewTaskMapper taskMapper;

    public TaskApplicationService(ReviewTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public TaskView create(CreateTaskCommand command, CurrentUser currentUser) {
        require(currentUser, Permission.CREATE_TASK);
        long id = sequence.incrementAndGet();
        ReviewTask task = ReviewTask.draft(id, command.reviewType(), command.taskName(), command.projectName(),
                command.designerId(), command.designName(), command.pcbType());
        tasks.put(id, task);
        taskMapper.insert(toRecord(task, 0L));
        return TaskView.from(task);
    }

    public TaskView submit(long taskId, List<Long> initialFileIds, CurrentUser currentUser) {
        ReviewTask task = requireTask(taskId);
        if (!task.designerId().equals(currentUser.id()) && !permissionPolicy.has(currentUser.roles(), Permission.CREATE_TASK)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权提交该任务");
        }
        initialFileIds.forEach(task::addInitialFile);
        task.submit();
        if (taskMapper.update(toRecord(task, 0L)) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "任务已被其他请求更新");
        }
        return TaskView.from(task);
    }

    public TaskView get(long taskId, CurrentUser currentUser) {
        ReviewTask task = requireTask(taskId);
        if (!permissionPolicy.canViewAllTasks(currentUser.roles()) && !task.designerId().equals(currentUser.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该任务");
        }
        return TaskView.from(task);
    }

    public List<TaskView> list(CurrentUser currentUser) {
        return taskMapper.findAll().stream()
                .map(this::restore)
                .filter(task -> permissionPolicy.canViewAllTasks(currentUser.roles()) || task.designerId().equals(currentUser.id()))
                .sorted(Comparator.comparing(ReviewTask::id))
                .map(TaskView::from)
                .toList();
    }

    private ReviewTask requireTask(long taskId) {
        return tasks.computeIfAbsent(taskId, id -> {
            ReviewTaskRecord record = taskMapper.findById(id);
            if (record == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
            return restore(record);
        });
    }

    private void require(CurrentUser currentUser, Permission permission) {
        if (!permissionPolicy.has(currentUser.roles(), permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无对应功能权限");
        }
    }

    public record CreateTaskCommand(ReviewType reviewType, String taskName, String projectName, Long designerId,
                                    String designName, String pcbType) {
    }

    public record TaskView(Long id, ReviewType reviewType, String taskName, String projectName, Long designerId,
                           String designName, String pcbType, String status) {
        static TaskView from(ReviewTask task) {
            return new TaskView(task.id(), task.reviewType(), task.taskName(), task.projectName(), task.designerId(),
                    task.designName(), task.pcbType(), task.status().name());
        }
    }

    private ReviewTaskRecord toRecord(ReviewTask task, long version) {
        ReviewTaskRecord record = new ReviewTaskRecord();
        record.setId(task.id()); record.setReviewType(task.reviewType().name()); record.setTaskName(task.taskName());
        record.setProjectName(task.projectName()); record.setDesignerId(task.designerId()); record.setDesignName(task.designName());
        record.setPcbType(task.pcbType()); record.setStatus(task.status().name());
        record.setInitialFileIds(task.initialFileIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setVersion(version); return record;
    }

    private ReviewTask restore(ReviewTaskRecord record) {
        List<Long> fileIds = record.getInitialFileIds().isBlank() ? List.of() : java.util.Arrays.stream(record.getInitialFileIds().split(",")).map(Long::valueOf).toList();
        return ReviewTask.restore(record.getId(), ReviewType.valueOf(record.getReviewType()), record.getTaskName(), record.getProjectName(), record.getDesignerId(), record.getDesignName(), record.getPcbType(), TaskStatus.valueOf(record.getStatus()), fileIds);
    }
}
