package com.leapmotor.pcbreview.notification.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 对应 outbox_event 表的本地事务事件记录，保存待投递事件的聚合定位、序列化载荷与投递状态，不直接发送邮件或消息。
 */
public record OutboxEventRecord(String eventType, String aggregateType, Long aggregateId, String payload, String status) {
}
