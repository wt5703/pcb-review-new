package com.bms.review.domain;

import java.util.Arrays;

/**
 * @author 王涛
 * @date 2026-09-29
 * @description 白名单可配置的人员类别。业务评审角色与互检单人员共用白名单，但两者不应混入 {@link ReviewRole}。
 */
public enum ReviewerWhitelistRole {
    HARDWARE_EXPERT("硬件评审", ReviewRole.HARDWARE_EXPERT),
    EMC_EXPERT("EMC评审", ReviewRole.EMC_EXPERT),
    PCB_EXPERT("PCB评审", ReviewRole.PCB_EXPERT),
    PROCESS_EXPERT("工艺评审", ReviewRole.PROCESS_EXPERT),
    STRUCTURE_EXPERT("结构评审", ReviewRole.STRUCTURE_EXPERT),
    PCB_MUTUAL_CHECK("PCB互检单评审", null),
    SCHEMATIC_MUTUAL_CHECK("原理图互检单评审", null);

    private final String displayName;
    private final ReviewRole reviewRole;

    ReviewerWhitelistRole(String displayName, ReviewRole reviewRole) {
        this.displayName = displayName;
        this.reviewRole = reviewRole;
    }

    public String displayName() {
        return displayName;
    }

    public ReviewRole reviewRole() {
        return reviewRole;
    }

    public static ReviewerWhitelistRole fromReviewRole(ReviewRole reviewRole) {
        return Arrays.stream(values())
                .filter(value -> value.reviewRole == reviewRole)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未配置白名单角色：" + reviewRole));
    }
}
