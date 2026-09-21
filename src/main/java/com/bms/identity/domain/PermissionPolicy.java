package com.bms.identity.domain;

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
        permissions.put(Role.PCB_LEADER, EnumSet.of(Permission.VIEW_CURRENT_TASK, Permission.VIEW_ALL_TASKS,
                Permission.UPLOAD_PCB_SCHEMATIC_FILE, Permission.DOWNLOAD_PCB_SCHEMATIC_FILE,
                Permission.UPLOAD_PROCESS_FILE, Permission.DOWNLOAD_PROCESS_FILE, Permission.UPLOAD_STRUCTURE_FILE,
                Permission.DOWNLOAD_STRUCTURE_FILE, Permission.UPLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.DOWNLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.UPLOAD_OPINION_ATTACHMENT, Permission.DOWNLOAD_OPINION_ATTACHMENT, Permission.MANAGE_USER, Permission.VIEW_USER,
                Permission.ASSIGN_HARDWARE_EXPERT, Permission.ASSIGN_EMC_EXPERT, Permission.ASSIGN_PROCESS_EXPERT, Permission.ASSIGN_STRUCTURE_EXPERT, Permission.ASSIGN_PCB_MUTUAL_CHECK, Permission.MANAGE_MUTUAL_CHECK,
                Permission.VIEW_MUTUAL_CHECK_OPINION, Permission.FINISH_PCB_TASK));
        permissions.put(Role.SCHEMATIC_LEADER, EnumSet.of(Permission.VIEW_CURRENT_TASK, Permission.VIEW_ALL_TASKS,
                Permission.DOWNLOAD_PROCESS_FILE, Permission.DOWNLOAD_STRUCTURE_FILE,
                Permission.ASSIGN_SCHEMATIC_MUTUAL_CHECK, Permission.FINISH_SCHEMATIC_TASK));
        permissions.put(Role.HARDWARE_EXPERT, EnumSet.of(Permission.VIEW_CURRENT_TASK, Permission.VIEW_ALL_TASKS,
                Permission.FILL_OPINION, Permission.CONFIRM_OPINION, Permission.VIEW_OPINION,
                Permission.DOWNLOAD_PROCESS_FILE, Permission.DOWNLOAD_STRUCTURE_FILE, Permission.UPLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.DOWNLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.UPLOAD_OPINION_ATTACHMENT, Permission.DOWNLOAD_OPINION_ATTACHMENT));
        permissions.put(Role.EMC_EXPERT, EnumSet.of(Permission.VIEW_CURRENT_TASK, Permission.VIEW_ALL_TASKS,
                Permission.FILL_OPINION, Permission.CONFIRM_OPINION, Permission.VIEW_OPINION,
                Permission.DOWNLOAD_PCB_SCHEMATIC_FILE, Permission.DOWNLOAD_PROCESS_FILE, Permission.DOWNLOAD_STRUCTURE_FILE, Permission.UPLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.DOWNLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.UPLOAD_OPINION_ATTACHMENT, Permission.DOWNLOAD_OPINION_ATTACHMENT));
        permissions.put(Role.DESIGNER, EnumSet.of(Permission.VIEW_CURRENT_TASK, Permission.VIEW_ALL_TASKS,
                Permission.CREATE_TASK, Permission.REPLY_OPINION, Permission.VIEW_OPINION,
                Permission.UPLOAD_PCB_SCHEMATIC_FILE, Permission.DOWNLOAD_PCB_SCHEMATIC_FILE,
                Permission.UPLOAD_PROCESS_FILE, Permission.DOWNLOAD_PROCESS_FILE, Permission.UPLOAD_STRUCTURE_FILE,
                Permission.DOWNLOAD_STRUCTURE_FILE, Permission.UPLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.DOWNLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.UPLOAD_OPINION_ATTACHMENT, Permission.DOWNLOAD_OPINION_ATTACHMENT, Permission.ASSIGN_PCB_EXPERT, Permission.ASSIGN_PCB_MUTUAL_CHECK, Permission.ASSIGN_SCHEMATIC_OTHER_EXPERT));
        permissions.put(Role.PROCESS_EXPERT, EnumSet.of(Permission.VIEW_CURRENT_TASK, Permission.FILL_OPINION,
                Permission.CONFIRM_OPINION, Permission.VIEW_OPINION, Permission.DOWNLOAD_PROCESS_FILE, Permission.UPLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.DOWNLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.UPLOAD_OPINION_ATTACHMENT, Permission.DOWNLOAD_OPINION_ATTACHMENT));
        permissions.put(Role.STRUCTURE_EXPERT, EnumSet.of(Permission.VIEW_CURRENT_TASK, Permission.FILL_OPINION,
                Permission.CONFIRM_OPINION, Permission.VIEW_OPINION, Permission.DOWNLOAD_STRUCTURE_FILE, Permission.UPLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.DOWNLOAD_MUTUAL_CHECK_ATTACHMENT, Permission.UPLOAD_OPINION_ATTACHMENT, Permission.DOWNLOAD_OPINION_ATTACHMENT));
    }

    public boolean has(Set<Role> roles, Permission permission) {
        return roles.stream().anyMatch(role -> permissions.getOrDefault(role, Set.of()).contains(permission));
    }

    public boolean canViewAllTasks(Set<Role> roles) {
        return has(roles, Permission.VIEW_ALL_TASKS);
    }

    public boolean canViewCurrentTask(Set<Role> roles, boolean assignedToTask) {
        return canViewAllTasks(roles) || (assignedToTask && has(roles, Permission.VIEW_CURRENT_TASK));
    }
}
