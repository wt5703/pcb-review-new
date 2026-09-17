package com.leapmotor.pcbreview.notification.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 定义通知发送尝试记录的追加写入接口；每次重试均新增独立记录，确保失败历史不会被后续成功覆盖。
 */
@Mapper
public interface NotificationSendRecordMapper {
    @Insert("INSERT INTO notification_send_record (outbox_event_id, event_type, recipient, template_code, delivery_status, failure_reason) "
            + "VALUES (#{outboxEventId}, #{eventType}, #{recipient}, #{templateCode}, #{deliveryStatus}, #{failureReason})")
    int insert(NotificationSendRecord record);
}
