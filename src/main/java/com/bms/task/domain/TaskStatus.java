package com.bms.task.domain;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 描述评审任务从待提交到已结束的整体生命周期状态；状态值仅表达任务所处阶段，具体节点准入规则由流程域负责。
 */


public enum TaskStatus {
    /** 草稿：任务已保存，尚未正式提交。 */
    DRAFT("草稿"),
    PENDING_INITIATION("待发起"),
    /** 专家评审：PCB 任务正在进行专家意见评审。 */
    PCB_EXPERT_REVIEWING("专家评审"),
    /** 工艺/结构评审：PCB 任务正在进行工艺和/或结构评审。 */
    PCB_PROCESS_STRUCTURE_REVIEWING("工艺/结构评审"),
    /** 互检单待分配：等待组长为互检单分配评审人员。 */
    MUTUAL_CHECK_PENDING_ASSIGNMENT("互检单待分配"),
    /** 互检单评审：已分配人员正在填写互检单。 */
    MUTUAL_CHECK_REVIEWING("互检单评审"),
    /** 硬件专家分配：原理图互检意见已闭环，等待硬件部开发经理分配硬件专家。 */
    SCHEMATIC_EXPERT_PENDING_ASSIGNMENT("硬件专家分配"),
    /** 原理图评审：互检意见闭环后，硬件部开发经理已指定的评审专家正在评审原理图。 */
    SCHEMATIC_REVIEWING("原理图评审"),
    /** 待结束：设计者已提交终版文件，等待组长结束任务。 */
    PENDING_FINISH("待结束"),
    /** 结束：任务流程已结束并可归档。 */
    FINISHED("结束"),
    TERMINATED("终止");

    private final String displayName;

    TaskStatus(String displayName) {
        this.displayName = displayName;
    }

    public boolean canWithdraw(ReviewType reviewType, boolean hasReviewOpinions) {
        return !hasReviewOpinions && (reviewType == ReviewType.PCB && this == PCB_EXPERT_REVIEWING
                || reviewType == ReviewType.SCHEMATIC && this == MUTUAL_CHECK_PENDING_ASSIGNMENT);
    }

    public boolean isPendingSubmission() {
        return this == DRAFT || this == PENDING_INITIATION;
    }

    public static boolean isClosed(String status) {
        return FINISHED.name().equals(status) || TERMINATED.name().equals(status);
    }

    public String displayName() {
        return displayName;
    }
}
