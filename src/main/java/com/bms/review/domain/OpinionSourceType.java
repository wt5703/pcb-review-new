package com.bms.review.domain;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 标记评审意见的业务来源，以便把专家意见、互检固定检查项问题和互检额外意见统一纳入同一闭环并保留来源追溯。
 */
public enum OpinionSourceType {
    EXPERT_REVIEW,
    PROCESS_REVIEW,
    STRUCTURE_REVIEW,
    MUTUAL_CHECK_ITEM,
    MUTUAL_EXTRA
}
