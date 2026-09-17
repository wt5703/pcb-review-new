package com.leapmotor.pcbreview.notification.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 定义本地 Outbox 事件的持久化接口，保障任务提交等业务变更与后续通知投递意图处于同一数据库事务。
 */
@Mapper
public interface OutboxEventMapper {
    @Insert("INSERT INTO outbox_event (event_type, aggregate_type, aggregate_id, payload, status) "
            + "VALUES (#{eventType}, #{aggregateType}, #{aggregateId}, #{payload}, #{status})")
    int insert(OutboxEventRecord record);
}
