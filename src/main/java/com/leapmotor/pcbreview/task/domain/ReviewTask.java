package com.leapmotor.pcbreview.task.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 评审任务聚合根，维护任务基础信息、初始文件关联、版本号和状态，并保证草稿提交等核心状态变更符合任务类型校验规则。
 */


public final class ReviewTask {
    private final Long id;
    private final ReviewType reviewType;
    private final String taskName;
    private final String projectName;
    private final Long designerId;
    private final String designName;
    private String pcbType;
    private final List<Long> initialFileIds = new ArrayList<>();
    private TaskStatus status = TaskStatus.DRAFT;

    private ReviewTask(Long id, ReviewType reviewType, String taskName, String projectName, Long designerId,
                       String designName, String pcbType) {
        this.id = Objects.requireNonNull(id);
        this.reviewType = Objects.requireNonNull(reviewType);
        this.taskName = requireText(taskName, "任务名称不能为空");
        this.projectName = requireText(projectName, "项目名称不能为空");
        this.designerId = Objects.requireNonNull(designerId);
        this.designName = requireText(designName, "设计名称不能为空");
        this.pcbType = pcbType;
    }

    public static ReviewTask draft(Long id, ReviewType reviewType, String taskName, String projectName,
                                   Long designerId, String designName, String pcbType) {
        return new ReviewTask(id, reviewType, taskName, projectName, designerId, designName, pcbType);
    }

    public static ReviewTask restore(Long id, ReviewType reviewType, String taskName, String projectName, Long designerId,
                                     String designName, String pcbType, TaskStatus status, List<Long> initialFileIds) {
        ReviewTask task = new ReviewTask(id, reviewType, taskName, projectName, designerId, designName, pcbType);
        task.initialFileIds.addAll(initialFileIds);
        task.status = status;
        return task;
    }

    public void setPcbType(String pcbType) {
        ensureEditable();
        this.pcbType = pcbType;
    }

    public void addInitialFile(Long fileId) {
        ensureEditable();
        initialFileIds.add(Objects.requireNonNull(fileId));
    }

    public void submit() {
        if (status != TaskStatus.DRAFT) {
            throw new IllegalStateException("当前任务不允许提交");
        }
        if (initialFileIds.isEmpty()) {
            throw new IllegalStateException("提交任务前必须关联初始设计文件");
        }
        if (reviewType == ReviewType.PCB && (pcbType == null || pcbType.isBlank())) {
            throw new IllegalStateException("PCB评审任务必须填写PCB类型");
        }
        status = reviewType == ReviewType.PCB ? TaskStatus.PCB_PENDING_REVIEW : TaskStatus.SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT;
    }

    public void moveTo(TaskStatus targetStatus) {
        ensureNotFinished();
        status = Objects.requireNonNull(targetStatus);
    }

    public void finish() {
        if (status != TaskStatus.PENDING_FINISH_CONFIRMATION) {
            throw new IllegalStateException("当前任务不允许结束");
        }
        status = TaskStatus.FINISHED;
    }

    private void ensureEditable() {
        ensureNotFinished();
        if (status != TaskStatus.DRAFT) {
            throw new IllegalStateException("提交后的任务不能修改草稿字段");
        }
    }

    private void ensureNotFinished() {
        if (status == TaskStatus.FINISHED) {
            throw new IllegalStateException("已结束任务不允许修改");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public Long id() { return id; }
    public ReviewType reviewType() { return reviewType; }
    public String taskName() { return taskName; }
    public String projectName() { return projectName; }
    public Long designerId() { return designerId; }
    public String designName() { return designName; }
    public String pcbType() { return pcbType; }
    public List<Long> initialFileIds() { return List.copyOf(initialFileIds); }
    public TaskStatus status() { return status; }
}
