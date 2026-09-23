package com.bms.workflow.domain;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 枚举任务流程域允许执行的状态推进动作；动作名称表达业务语义，避免在接口或应用服务中散落状态字符串。
 */
public enum WorkflowAction {
    CREATE,
    START_PCB_STRUCTURE_REVIEW,
    START_PCB_PROCESS_REVIEW,
    START_PCB_MATUAL_ASSIGNMENT,
    START_PCB_MATUAL_REVIEW,
    START_SCHEMATIC_MATUAL_REVIEW,
    START_SCHEMATIC_EXPERT_ASSIGNMENT,
    START_SCHEMATIC_EXPERT_REVIEW,
    PREPARE_FINISH,
    FINISH
}
