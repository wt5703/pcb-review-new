package com.bms.task.domain;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 描述评审任务从待提交到已结束的整体生命周期状态；状态值仅表达任务所处阶段，具体节点准入规则由流程域负责。
 */


public enum TaskStatus {
    /** 草稿：任务已保存，尚未正式提交。 */
    DRAFT("草稿"),
    /** 专家评审：PCB 任务正在进行专家意见评审。 */
    PCB_EXPERT_REVIEWING("专家评审"),
    /** 工艺/结构评审：PCB 任务正在进行工艺和/或结构评审。 */
    PCB_PROCESS_STRUCTURE_REVIEWING("工艺/结构评审"),
    /** 互检单待分配：等待组长为互检单分配评审人员。 */
    MUTUAL_CHECK_PENDING_ASSIGNMENT("互检单待分配"),
    /** 互检单评审：已分配人员正在填写互检单。 */
    MUTUAL_CHECK_REVIEWING("互检单评审"),
    /** 待分配硬件专家：原理图互检完成，等待硬件专家分配。 */
    SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT("待分配硬件专家"),
    /** 原理图评审：已分配硬件专家正在评审原理图。 */
    SCHEMATIC_REVIEWING("原理图评审"),
    /** 结束：任务流程已结束并可归档。 */
    FINISHED("结束");

    private final String displayName;

    TaskStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
