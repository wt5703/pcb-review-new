package com.bms.workflow.domain;

import com.bms.identity.domain.Permission;

/**
 * @author 王涛
 * @date 2026-09-29
 * @description 流程节点中的人员分配职责及其操作权限。它与任务业务评审角色 {@code ReviewRole} 分离。
 */
public enum WorkflowAssignmentRole {
    PCB_MUTUAL_CHECK("PCB互检单评审", Permission.ASSIGN_PCB_MUTUAL_CHECK),
    SCHEMATIC_MUTUAL_CHECK("原理图互检单评审", Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK),
    SCHEMATIC_HARDWARE_EXPERT("原理图硬件专家评审", Permission.ASSIGN_SCHEMATIC_HARDWARE_EXPERT),
    SCHEMATIC_OTHER_EXPERT("原理图其他专家评审", Permission.ASSIGN_SCHEMATIC_OTHER_EXPERT);

    private final String displayName;
    private final Permission assignmentPermission;

    WorkflowAssignmentRole(String displayName, Permission assignmentPermission) {
        this.displayName = displayName;
        this.assignmentPermission = assignmentPermission;
    }

    public String displayName() {
        return displayName;
    }

    public Permission assignmentPermission() {
        return assignmentPermission;
    }
}
