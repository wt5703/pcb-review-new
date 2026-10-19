package com.bms.workflow.domain;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 枚举任务流程域允许执行的状态推进动作；动作名称表达业务语义，避免在接口或应用服务中散落状态字符串。
 */
public enum WorkflowAction {
    CREATE("创建任务"),
    START_PCB_STRUCTURE_REVIEW("开启结构评审"),
    START_PCB_PROCESS_REVIEW("开启工艺评审"),
    START_PCB_MATUAL_ASSIGNMENT("开启互检单分配"),
    START_PCB_MATUAL_REVIEW("开启互检单评审"),
    START_SCHEMATIC_MATUAL_REVIEW("开启互检单评审"),
    START_SCHEMATIC_EXPERT_REVIEW("开启原理图专家评审"),
    /** 设计者提交终版并将任务流转至待结束；与同名的任务状态分别表示动作和结果。 */
    PENDING_FINISH("进入待结束"),
    FINISH("结束任务");

    private final String actionName;

    WorkflowAction(String actionName) {
        this.actionName = actionName;
    }

    public String actionName() {
        return actionName;
    }
}
