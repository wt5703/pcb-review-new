package com.bms.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 表示设计者对评审意见的处理方式，区分已修改、未修改等答复语义，供评审人执行通过或不通过确认。
 */


public enum ReplyType {
    ACCEPT,
    ACCEPT_NO_CHANGE,
    REJECT
}
