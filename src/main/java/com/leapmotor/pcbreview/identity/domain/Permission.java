package com.leapmotor.pcbreview.identity.domain;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 定义平台受保护操作对应的功能权限标识，是角色授权规则与应用服务访问控制之间的统一契约。
 */


public enum Permission {
    CREATE_TASK,
    FILL_OPINION,
    REPLY_OPINION,
    CONFIRM_OPINION,
    DOWNLOAD_DESIGN_FILE,
    ASSIGN_PCB_EXPERT,
    ASSIGN_PCB_MUTUAL_CHECK,
    ASSIGN_SCHEMATIC_HARDWARE_EXPERT,
    ASSIGN_SCHEMATIC_OTHER_EXPERT,
    ASSIGN_SCHEMATIC_MUTUAL_CHECK,
    FINISH_PCB_TASK,
    FINISH_SCHEMATIC_TASK
}
