package com.bms.task.domain;

import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import com.bms.review.domain.ReviewRole;

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
    private final String designerName;
    private final String designName;
    private String pcbType;
    private final LocalDate expectedCompletedDate;
    private final Long expertLeaderId;
    private final String expertLeaderName;
    private final List<ReviewRole> reviewRoles;
    private final String reviewDescription;
    private final List<Long> initialFileIds = new ArrayList<>();
    private TaskStatus status = TaskStatus.DRAFT;

    private ReviewTask(Long id, ReviewType reviewType, String taskName, String projectName, Long designerId,
                       String designerName, String designName, String pcbType, LocalDate expectedCompletedDate,
                       Long expertLeaderId, String expertLeaderName, List<ReviewRole> reviewRoles, String reviewDescription) {
        this.id = Objects.requireNonNull(id);
        this.reviewType = Objects.requireNonNull(reviewType);
        this.taskName = requireText(taskName, "任务名称不能为空");
        this.projectName = requireText(projectName, "项目名称不能为空");
        this.designerId = Objects.requireNonNull(designerId);
        this.designerName = requireText(designerName, "设计者姓名不能为空");
        this.designName = requireText(designName, "设计名称不能为空");
        this.pcbType = pcbType;
        this.expectedCompletedDate = Objects.requireNonNull(expectedCompletedDate, "期望完成日期不能为空");
        this.expertLeaderId = Objects.requireNonNull(expertLeaderId, "专家/组长不能为空");
        this.expertLeaderName = requireText(expertLeaderName, "专家/组长姓名不能为空");
        this.reviewRoles = List.copyOf(Objects.requireNonNull(reviewRoles));
        if (this.reviewRoles.isEmpty()) { throw new IllegalArgumentException("评审角色不能为空"); }
        this.reviewDescription = reviewDescription;
    }

    public static ReviewTask draft(Long id, ReviewType reviewType, String taskName, String projectName,
                                   Long designerId, String designerName, String designName, String pcbType, LocalDate expectedCompletedDate,
                                   Long expertLeaderId, String expertLeaderName, List<ReviewRole> reviewRoles, String reviewDescription) {
        return new ReviewTask(id, reviewType, taskName, projectName, designerId, designerName, designName, pcbType,
                expectedCompletedDate, expertLeaderId, expertLeaderName, reviewRoles, reviewDescription);
    }

    /**
     * @author 王涛
     * @date 2026-09-18
     * @description 兼容既有领域测试和旧调用方的草稿构造入口；生产接口必须使用包含设计者姓名、日期、专家和评审角色的完整参数版本。
     */
    public static ReviewTask draft(Long id, ReviewType reviewType, String taskName, String projectName,
                                   Long designerId, String designName, String pcbType) {
        return draft(id, reviewType, taskName, projectName, designerId, "设计者#" + designerId, designName, pcbType,
                LocalDate.now(), designerId, "专家/组长#" + designerId, List.of(ReviewRole.PCB_EXPERT), null);
    }

    public static ReviewTask restore(Long id, ReviewType reviewType, String taskName, String projectName, Long designerId,
                                     String designerName, String designName, String pcbType, LocalDate expectedCompletedDate,
                                     Long expertLeaderId, String expertLeaderName, List<ReviewRole> reviewRoles, String reviewDescription,
                                     TaskStatus status, List<Long> initialFileIds) {
        ReviewTask task = new ReviewTask(id, reviewType, taskName, projectName, designerId, designerName, designName, pcbType,
                expectedCompletedDate, expertLeaderId, expertLeaderName, reviewRoles, reviewDescription);
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
        status = reviewType == ReviewType.PCB ? TaskStatus.PCB_PENDING_REVIEW : TaskStatus.SCHEMATIC_PENDING_LEADER_ASSIGNMENT;
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
    public String designerName() { return designerName; }
    public String designName() { return designName; }
    public String pcbType() { return pcbType; }
    public LocalDate expectedCompletedDate() { return expectedCompletedDate; }
    public Long expertLeaderId() { return expertLeaderId; }
    public String expertLeaderName() { return expertLeaderName; }
    public List<ReviewRole> reviewRoles() { return reviewRoles; }
    public String reviewDescription() { return reviewDescription; }
    public List<Long> initialFileIds() { return List.copyOf(initialFileIds); }
    public TaskStatus status() { return status; }
}
