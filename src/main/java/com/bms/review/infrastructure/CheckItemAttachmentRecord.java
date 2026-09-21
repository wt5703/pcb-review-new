package com.bms.review.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-15
 * @description 映射任务内检查项与已授权文件的附件关联，保存显示顺序而不复制文件二进制或独立维护文件。
 */
public record CheckItemAttachmentRecord(Long checkItemId, Long fileId, int sortNo) {
}
