package com.bms.notification.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射一次邮件发送尝试；无论成功或失败均保留任务、邮件类型、主题、To、CC、附件和失败原因，便于排查与归档。
 */
public record NotificationSendRecord(Long outboxEventId, Long taskId, String eventType, String recipient,
                                     String carbonCopies, String attachments, String subject, String templateCode,
                                     String deliveryStatus, String failureReason, LocalDateTime attemptedAt) {
}
