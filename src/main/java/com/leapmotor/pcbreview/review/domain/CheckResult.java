package com.leapmotor.pcbreview.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 表示互检固定检查项的结论；不合格和不适用均要求留下可追溯说明，不合格还必须关联后续意见闭环。
 */
public enum CheckResult {
    PASS,
    FAIL,
    NOT_APPLICABLE
}
