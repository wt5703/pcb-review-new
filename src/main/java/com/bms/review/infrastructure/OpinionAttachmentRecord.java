package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 记录专家评审意见与已登记图片文件之间的关联及展示顺序，文件元数据仍由 review_file 统一维护。
 */
public record OpinionAttachmentRecord(Long opinionId, Long fileId, int sortNo) {
}
