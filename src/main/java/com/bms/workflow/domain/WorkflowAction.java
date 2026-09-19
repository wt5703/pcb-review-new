package com.bms.workflow.domain;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 枚举任务流程域允许执行的状态推进动作；动作名称表达业务语义，避免在接口或应用服务中散落状态字符串。
 */
public enum WorkflowAction {
    START_PCB_EXPERT_REVIEW,
    START_PCB_OPTIONAL_REVIEW,
    PREPARE_PCB_MUTUAL_ASSIGNMENT,
    START_PCB_MUTUAL_REVIEW,
    START_SCHEMATIC_MUTUAL_REVIEW,
    PREPARE_SCHEMATIC_EXPERT_ASSIGNMENT,
    START_SCHEMATIC_EXPERT_REVIEW,
    REQUEST_FINISH,
    FINISH
}
