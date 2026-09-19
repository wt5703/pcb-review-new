package com.bms.review.domain;

import com.bms.identity.domain.Permission;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 定义任务中可被分配的评审与互检职责，并将分配动作关联到相应的功能权限，支持同一职责下多人并行处理。
 */
public enum ReviewRole {
    PCB_EXPERT(Permission.ASSIGN_PCB_EXPERT),
    PROCESS_EXPERT(Permission.ASSIGN_PROCESS_EXPERT),
    STRUCTURE_EXPERT(Permission.ASSIGN_STRUCTURE_EXPERT),
    PCB_MUTUAL_CHECK(Permission.ASSIGN_PCB_MUTUAL_CHECK),
    SCHEMATIC_HARDWARE_EXPERT(Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT),
    SCHEMATIC_OTHER_EXPERT(Permission.ASSIGN_SCHEMATIC_OTHER_EXPERT),
    SCHEMATIC_MUTUAL_CHECK(Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK);

    private final Permission assignmentPermission;

    ReviewRole(Permission assignmentPermission) {
        this.assignmentPermission = assignmentPermission;
    }

    public Permission assignmentPermission() {
        return assignmentPermission;
    }
}
