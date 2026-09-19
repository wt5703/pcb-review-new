package com.bms.notification.infrastructure;

import java.time.LocalDateTime;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射一次通知发送尝试的可追溯结果，记录关联 Outbox、模板代号、当前阶段占位收件人、成功或失败状态及失败原因。
 */
public record NotificationSendRecord(Long outboxEventId, String eventType, String recipient, String templateCode,
                                     String deliveryStatus, String failureReason, LocalDateTime attemptedAt) {
}
