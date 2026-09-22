package com.bms.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 表示互检固定检查项的结论；FAIL 和 NC 均通过 richText 留下可追溯图文说明，其中 FAIL 自动进入意见闭环。
 */
public enum CheckResult {
    PASS,
    FAIL,
    NC
}
