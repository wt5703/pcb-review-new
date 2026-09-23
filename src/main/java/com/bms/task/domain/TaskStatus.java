package com.bms.task.domain;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 描述评审任务从待提交到已结束的整体生命周期状态；状态值仅表达任务所处阶段，具体节点准入规则由流程域负责。
 */


public enum TaskStatus {
    DRAFT("草稿"),
    PCB_EXPERT_REVIEWING("专家评审"),
    PCB_PROCESS_STRUCTURE_REVIEWING("工艺/结构评审"),
    MUTUAL_CHECK_PENDING_ASSIGNMENT("互检单待分配"),
    MUTUAL_CHECK_REVIEWING("互检单评审"),
    SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT("待分配硬件专家"),
    SCHEMATIC_REVIEWING("原理图评审"),
    FINISHED("结束");

    private final String displayName;

    TaskStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
