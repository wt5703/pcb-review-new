package com.leapmotor.pcbreview.domain;

import com.leapmotor.pcbreview.identity.domain.Permission;
import com.leapmotor.pcbreview.identity.domain.PermissionPolicy;
import com.leapmotor.pcbreview.identity.domain.Role;
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
    void emcExpertMayDownloadDesignFileButHardwareExpertMayNot() {
        assertThat(policy.has(Set.of(Role.EMC_EXPERT), Permission.DOWNLOAD_DESIGN_FILE)).isTrue();
        assertThat(policy.has(Set.of(Role.HARDWARE_EXPERT), Permission.DOWNLOAD_DESIGN_FILE)).isFalse();
    }

    @Test
    void processAndStructureExpertsCannotViewAllTasks() {
        assertThat(policy.canViewAllTasks(Set.of(Role.PROCESS_EXPERT))).isFalse();
        assertThat(policy.canViewAllTasks(Set.of(Role.STRUCTURE_EXPERT))).isFalse();
        assertThat(policy.canViewAllTasks(Set.of(Role.DESIGNER))).isTrue();
    }
}
