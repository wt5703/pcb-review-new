package com.bms.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 表示互检固定检查项的结论；FAIL 必须填写意见并自动进入意见闭环，NC 可不填写文字或富文本说明。
 */
public enum CheckResult {
    PASS,
    FAIL,
    NC
}
