package com.bms.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 描述一条评审意见从提出、设计者答复到评审人确认或撤回的闭环状态，用于限制各角色可执行的后续动作。
 */


public enum OpinionStatus {
    PENDING_REPLY,
    PENDING_CONFIRMATION,
    CONFIRMED_PASS,
    CONFIRMED_REJECTED,
    WITHDRAWN
}
