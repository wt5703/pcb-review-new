package com.bms.identity.domain;

/** PCB 评审工具在用户中心（LEAPENERGY_PCB）中配置的系统角色编码。 */
public enum SystemRole {
    PCB_LEADER("PCB-LEADER"),
    PLAIN_USER("PLAIN-USER"),
    ADMIN("ADMIN"),
    HARDWARE_MANAGER("HARDWARE-MANAGER"),
    SCHEMATIC_LEADER("SCHEMATIC-LEADER");

    private final String code;

    SystemRole(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
