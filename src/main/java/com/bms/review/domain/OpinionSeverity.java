package com.bms.review.domain;

/**
 * @author 王涛
 * @date 2026-10-10
 * @description 评审意见的严重等级；持久化和接口均使用枚举名称，展示层可使用 displayName。
 */
public enum OpinionSeverity {
    SERIOUS("严重"),
    GENERAL("一般"),
    MINOR("轻微");

    private final String displayName;

    OpinionSeverity(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
