package com.bms.domain;

import com.bms.identity.domain.Permission;
import com.bms.identity.domain.PermissionPolicy;
import com.bms.identity.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 权限策略领域测试类。
 */


class PermissionPolicyTest {

    private final PermissionPolicy policy = new PermissionPolicy();

    @Test
    void shouldApplyConfirmedPcbAndSchematicFileDownloadMatrix() {
        assertThat(policy.has(Set.of(Role.EMC_EXPERT), Permission.DOWNLOAD_PCB_SCHEMATIC_FILE)).isTrue();
        assertThat(policy.has(Set.of(Role.HARDWARE_EXPERT), Permission.DOWNLOAD_PCB_SCHEMATIC_FILE)).isFalse();
        assertThat(policy.has(Set.of(Role.PCB_LEADER), Permission.UPLOAD_PCB_SCHEMATIC_FILE)).isTrue();
        assertThat(policy.has(Set.of(Role.SCHEMATIC_LEADER), Permission.UPLOAD_PCB_SCHEMATIC_FILE)).isFalse();
    }

    @Test
    void shouldApplyConfirmedProcessAndStructureFileDownloadMatrix() {
        assertThat(policy.has(Set.of(Role.PROCESS_EXPERT), Permission.DOWNLOAD_PROCESS_FILE)).isTrue();
        assertThat(policy.has(Set.of(Role.PROCESS_EXPERT), Permission.DOWNLOAD_STRUCTURE_FILE)).isFalse();
        assertThat(policy.has(Set.of(Role.STRUCTURE_EXPERT), Permission.DOWNLOAD_PROCESS_FILE)).isFalse();
        assertThat(policy.has(Set.of(Role.STRUCTURE_EXPERT), Permission.DOWNLOAD_STRUCTURE_FILE)).isTrue();
    }

    @Test
    void processAndStructureExpertsCannotViewAllTasks() {
        assertThat(policy.canViewAllTasks(Set.of(Role.PROCESS_EXPERT))).isFalse();
        assertThat(policy.canViewAllTasks(Set.of(Role.STRUCTURE_EXPERT))).isFalse();
        assertThat(policy.canViewAllTasks(Set.of(Role.DESIGNER))).isTrue();
    }

    @Test
    void processAndStructureExpertsMayOnlyViewAssignedCurrentTasks() {
        assertThat(policy.canViewCurrentTask(Set.of(Role.PROCESS_EXPERT), true)).isTrue();
        assertThat(policy.canViewCurrentTask(Set.of(Role.PROCESS_EXPERT), false)).isFalse();
        assertThat(policy.canViewCurrentTask(Set.of(Role.STRUCTURE_EXPERT), true)).isTrue();
        assertThat(policy.canViewCurrentTask(Set.of(Role.STRUCTURE_EXPERT), false)).isFalse();
    }
}
