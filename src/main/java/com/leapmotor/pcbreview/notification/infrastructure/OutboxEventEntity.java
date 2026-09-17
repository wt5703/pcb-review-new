package com.leapmotor.pcbreview.notification.infrastructure;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 映射待投递 Outbox 事件的完整持久化状态，包含事件标识、载荷和重试次数，供通知消费端以状态机方式保证幂等处理。
 */
public class OutboxEventEntity {
    private Long id;
    private String eventType;
    private String aggregateType;
    private Long aggregateId;
    private String payload;
    private String status;
    private Integer retryCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public Long getAggregateId() { return aggregateId; }
    public void setAggregateId(Long aggregateId) { this.aggregateId = aggregateId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
}
