package com.leapmotor.pcbreview.task.domain;

/**
 * @author 王涛
 * @date 2026-09-11
 * @description 描述评审任务从待提交到已结束的整体生命周期状态；状态值仅表达任务所处阶段，具体节点准入规则由流程域负责。
 */


public enum TaskStatus {
    DRAFT,
    PCB_PENDING_REVIEW,
    PCB_EXPERT_REVIEWING,
    PCB_OPTIONAL_REVIEWING,
    PENDING_MUTUAL_ASSIGNMENT,
    MUTUAL_REVIEWING,
    SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT,
    SCHEMATIC_PENDING_REVIEW,
    HARDWARE_REVIEWING,
    PENDING_FINISH_CONFIRMATION,
    FINISHED
}
