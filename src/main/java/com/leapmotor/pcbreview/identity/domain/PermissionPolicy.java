package com.leapmotor.pcbreview.identity.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 根据用户角色、目标任务归属及所需功能权限执行授权判定；同时承载工艺和结构专家受限、其他角色可查看全部任务的数据范围规则。
 */


public final class PermissionPolicy {
    private final Map<Role, Set<Permission>> permissions = new EnumMap<>(Role.class);

    public PermissionPolicy() {
        permissions.put(Role.HARDWARE_DEPARTMENT_MANAGER, EnumSet.allOf(Permission.class));
        permissions.put(Role.DESIGNER, EnumSet.of(Permission.CREATE_TASK, Permission.REPLY_OPINION,
                Permission.ASSIGN_PCB_EXPERT, Permission.ASSIGN_SCHEMATIC_OTHER_EXPERT, Permission.DOWNLOAD_DESIGN_FILE));
        permissions.put(Role.PCB_LEADER, EnumSet.of(Permission.DOWNLOAD_DESIGN_FILE,
                Permission.ASSIGN_PCB_MUTUAL_CHECK, Permission.FINISH_PCB_TASK));
        permissions.put(Role.SCHEMATIC_LEADER, EnumSet.of(Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK,
                Permission.FINISH_SCHEMATIC_TASK));
        permissions.put(Role.HARDWARE_EXPERT, EnumSet.of(Permission.FILL_OPINION, Permission.CONFIRM_OPINION));
        permissions.put(Role.EMC_EXPERT, EnumSet.of(Permission.FILL_OPINION, Permission.CONFIRM_OPINION,
                Permission.DOWNLOAD_DESIGN_FILE));
        permissions.put(Role.PROCESS_EXPERT, EnumSet.of(Permission.FILL_OPINION, Permission.CONFIRM_OPINION));
        permissions.put(Role.STRUCTURE_EXPERT, EnumSet.of(Permission.FILL_OPINION, Permission.CONFIRM_OPINION));
    }

    public boolean has(Set<Role> roles, Permission permission) {
        return roles.stream().anyMatch(role -> permissions.getOrDefault(role, Set.of()).contains(permission));
    }

    public boolean canViewAllTasks(Set<Role> roles) {
        return roles.stream().noneMatch(role -> role == Role.PROCESS_EXPERT || role == Role.STRUCTURE_EXPERT)
                || roles.stream().anyMatch(role -> role != Role.PROCESS_EXPERT && role != Role.STRUCTURE_EXPERT);
    }
}
