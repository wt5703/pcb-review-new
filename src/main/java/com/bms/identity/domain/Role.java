package com.bms.identity.domain;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 定义 PCB 评审平台中的业务角色，作为任务可见性、操作权限、文件下载授权和流程节点职责判断的基础。
 */


public enum Role {
    HARDWARE_DEPARTMENT_MANAGER,
    PCB_LEADER,
    SCHEMATIC_LEADER,
    HARDWARE_EXPERT,
    EMC_EXPERT,
    DESIGNER,
    PROCESS_EXPERT,
    STRUCTURE_EXPERT
}
