package com.bms.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 任务创建时选择的业务评审角色。该枚举只表达“由谁评审什么”，不承担流程节点分配或权限控制职责。
 */
public enum ReviewRole {
    HARDWARE_EXPERT("硬件评审"),
    EMC_EXPERT("EMC评审"),
    PCB_EXPERT("PCB评审"),
    PROCESS_EXPERT("工艺评审"),
    STRUCTURE_EXPERT("结构评审");

    private final String displayName;

    ReviewRole(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
