package com.bms.notification.application;

import com.bms.notification.infrastructure.OutboxEventMapper;
import com.bms.notification.infrastructure.OutboxEventRecord;
import org.springframework.stereotype.Service;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 为各业务域统一写入本地 Outbox 通知事件，确保业务事务提交后才由通知域异步投递，避免业务代码直接依赖邮件实现。
 */
@Service
public class OutboxEventPublisher {
    private static final String REVIEW_TASK_AGGREGATE = "REVIEW_TASK";
    private final OutboxEventMapper outboxEventMapper;

    public OutboxEventPublisher(OutboxEventMapper outboxEventMapper) {
        this.outboxEventMapper = outboxEventMapper;
    }

    public void publishTaskEvent(String eventType, long taskId, long operatorId) {
        String payload = "{\"eventType\":\"" + eventType + "\",\"taskId\":" + taskId
                + ",\"operatorId\":" + operatorId + "}";
        outboxEventMapper.insert(new OutboxEventRecord(eventType, REVIEW_TASK_AGGREGATE, taskId, payload, "PENDING"));
    }
}
