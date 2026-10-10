package com.bms.identity.domain;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 定义 PCB 评审平台中的业务角色，作为任务可见性、操作权限、文件下载授权和流程节点职责判断的基础。
 */


public enum Role {
    /** 硬件开发部经理：管理全局评审配置。 */
    HARDWARE_DEPARTMENT_MANAGER("硬件开发部经理"),
    /** PCB 组长：负责 PCB 互检单分配及 PCB 任务结束确认。 */
    PCB_LEADER("PCB组长"),
    /** 原理图组长：负责原理图互检单分配及原理图任务结束确认。 */
    SCHEMATIC_LEADER("原理图组长"),
    /** 硬件评审：提出、确认硬件/原理图评审意见。 */
    HARDWARE_EXPERT("硬件评审"),
    /** EMC 评审：提出、确认 EMC 评审意见。 */
    EMC_EXPERT("EMC评审"),
    /** 设计者：创建任务、上传设计文件并答复意见。 */
    DESIGNER("设计者"),
    /** 工艺评审：提出、确认工艺评审意见。 */
    PROCESS_EXPERT("工艺评审"),
    /** 结构评审：提出、确认结构评审意见。 */
    STRUCTURE_EXPERT("结构评审"),
    /** PCB 互检单评审：处理被分配 PCB 任务中的互检检查项和互检意见。 */
    PCB_MUTUAL_CHECK("PCB互检单评审"),
    /** 原理图互检单评审：处理被分配原理图任务中的互检检查项和互检意见。 */
    SCHEMATIC_MUTUAL_CHECK("原理图互检单评审");

    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    /** 返回用于页面、日志及字典展示的中文角色名称。 */
    public String displayName() {
        return displayName;
    }
}
