package com.bms.notification.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 定义本地 Outbox 事件的持久化接口，保障任务提交等业务变更与后续通知投递意图处于同一数据库事务。
 */
@Mapper
public interface OutboxEventMapper {
    @Insert("INSERT INTO outbox_event (event_type, aggregate_type, aggregate_id, payload, event_status) "
            + "VALUES (#{eventType}, #{aggregateType}, #{aggregateId}, #{payload}, #{status})")
    int insert(OutboxEventRecord record);

    @Select("SELECT id, event_type AS eventType, aggregate_type AS aggregateType, aggregate_id AS aggregateId, payload, event_status AS status, retry_count AS retryCount "
            + "FROM outbox_event WHERE event_status IN ('PENDING', 'FAILED') AND retry_count < #{maxRetries} ORDER BY id LIMIT #{limit}")
    List<OutboxEventEntity> findDispatchable(int limit, int maxRetries);

    @Update("UPDATE outbox_event SET event_status='PROCESSING' WHERE id=#{id} AND event_status IN ('PENDING', 'FAILED')")
    int claimForDispatch(long id);

    @Update("UPDATE outbox_event SET event_status='PUBLISHED', published_at=CURRENT_TIMESTAMP WHERE id=#{id} AND event_status='PROCESSING'")
    int markPublished(long id);

    @Update("UPDATE outbox_event SET event_status='FAILED', retry_count=retry_count+1 WHERE id=#{id} AND event_status='PROCESSING'")
    int markFailed(long id);

    @Select("SELECT id, event_type AS eventType, aggregate_type AS aggregateType, aggregate_id AS aggregateId, payload, event_status AS status, retry_count AS retryCount "
            + "FROM outbox_event WHERE aggregate_type=#{aggregateType} AND aggregate_id=#{aggregateId} ORDER BY id")
    List<OutboxEventEntity> findByAggregate(String aggregateType, long aggregateId);
}
